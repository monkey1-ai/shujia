import axios from 'axios'

// 统一 axios 实例：评测等一次性返回的接口走这里，后端返回 { code, message, traceId, data } 外壳
const http = axios.create({
  timeout: 120000
})

// 响应拦截：成功直接返回 Result 外壳，失败归一化成带 message 的 Error
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const result = error.response?.data
    const message = result?.message || error.message || '网络异常，请稍后重试'
    const wrapped = new Error(message)
    wrapped.code = result?.code ?? error.response?.status ?? -1
    wrapped.traceId = result?.traceId
    return Promise.reject(wrapped)
  }
)

/**
 * RAG 问答（SSE 流式）
 * 后端推 4 类事件：
 *   session → sessionId
 *   content → 文本增量（可能多次）
 *   done    → {"costMs":N}，缓存命中时为空串
 *   error   → 错误信息
 *
 * 注意：EventSource 只支持 GET，axios 在浏览器端拿不到增量流，
 * 因此这里用 fetch + ReadableStream 手动解析 SSE。
 *
 * @param {{question:string, topk?:number, sessionId?:string}} payload
 * @param {{onSession?:Function, onContent?:Function, onDone?:Function, onError?:Function, signal?:AbortSignal}} handlers
 * @returns {Promise<{sessionId:string, answer:string, costMs:number|null, cached:boolean}>}
 */
export async function ragSearchStream(payload, handlers = {}) {
  const { onSession, onContent, onDone, onError, signal } = handlers

  let response
  try {
    response = await fetch('/rag/search', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify(payload),
      signal
    })
  } catch (e) {
    // 主动中止不算错误，交给调用方忽略
    if (e.name === 'AbortError') throw e
    const err = new Error('无法连接后端服务')
    err.code = -1
    throw err
  }

  // 参数校验失败(400) / 限流(429) 时后端返回的是 JSON 而非事件流
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !contentType.includes('text/event-stream')) {
    let message = `请求失败（HTTP ${response.status}）`
    let traceId
    try {
      const body = await response.json()
      message = body?.message || message
      traceId = body?.traceId
    } catch {
      /* 非 JSON 响应，保持默认文案 */
    }
    const err = new Error(message)
    err.code = response.status
    err.traceId = traceId
    throw err
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')

  const state = { sessionId: payload.sessionId || '', answer: '', costMs: null, cached: false }
  let buffer = ''
  let serverError = null

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 以空行分隔事件块
    let sep
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const rawEvent = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const event = parseEvent(rawEvent)
      if (!event) continue

      switch (event.name) {
        case 'session':
          state.sessionId = event.data
          onSession && onSession(event.data)
          break
        case 'content':
          state.answer += event.data
          onContent && onContent(event.data)
          break
        case 'done': {
          // 缓存命中时 done 的 data 为空串，此时无耗时信息
          if (event.data) {
            try {
              const parsed = JSON.parse(event.data)
              state.costMs = parsed.costMs ?? null
              state.cached = !!parsed.cached
            } catch {
              state.costMs = null
            }
          } else {
            state.cached = true
          }
          onDone && onDone({ costMs: state.costMs, cached: state.cached })
          break
        }
        case 'error':
          serverError = event.data || '模型调用失败'
          break
      }
    }
  }

  if (serverError) {
    const err = new Error(serverError)
    err.code = 500
    onError && onError(err)
    throw err
  }

  return state
}

// 解析单个 SSE 事件块，兼容 "event: name" / "data: xxx" 及多行 data
function parseEvent(raw) {
  let name = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (dataLines.length === 0 && name === 'message') return null
  return { name, data: dataLines.join('\n') }
}

/**
 * 运行评测集
 * @param {number} [topK] 检索条数，不传则由后端取配置默认值
 * @returns {Promise<{hitRate:number, mrr:number, totalQueries:number, hits:number, details:string[]}>}
 */
export async function runEval(topK) {
  const params = {}
  if (topK != null) params.topK = topK
  const res = await http.get('/api/v1/eval/run', { params })
  if (res.code !== 200) {
    const err = new Error(res.message || '评测失败')
    err.code = res.code
    err.traceId = res.traceId
    throw err
  }
  return res.data
}

/**
 * 清空指定会话的记忆
 * @param {string} sessionId
 */
export async function cleanMemory(sessionId) {
  const res = await http.post('/cleanMemory/clean', null, { params: { sessionId } })
  return res
}

/**
 * 新建会话：sessionId 由后端颁发，前端不再本地生成
 * @returns {Promise<string>}
 */
export async function createSession() {
  const res = await http.post('/api/v1/chat/session')
  if (res.code !== 200) {
    const err = new Error(res.message || '新建会话失败')
    err.code = res.code
    err.traceId = res.traceId
    throw err
  }
  return res.data
}

/**
 * 会话列表（最近活跃的在前）
 * @returns {Promise<Array<{sessionId:string, title:string, messageCount:number, updatedAt:string}>>}
 */
export async function listSessions() {
  const res = await http.get('/api/v1/chat/session')
  if (res.code !== 200) {
    const err = new Error(res.message || '获取会话列表失败')
    err.code = res.code
    err.traceId = res.traceId
    throw err
  }
  return res.data
}

/**
 * 获取某个会话的历史消息
 * @param {string} sessionId
 * @returns {Promise<string[]>} 形如 ["user:xxx", "assistant:yyy", ...]
 */
export async function fetchHistory(sessionId) {
  const res = await http.get(`/api/v1/chat/session/${encodeURIComponent(sessionId)}/messages`)
  if (res.code !== 200) {
    const err = new Error(res.message || '获取历史失败')
    err.code = res.code
    err.traceId = res.traceId
    throw err
  }
  return res.data
}

/**
 * 删除某个会话：会话行、PG 消息、Redis 记忆、答案缓存一起删
 * @param {string} sessionId
 */
export async function deleteSession(sessionId) {
  const res = await http.delete(`/api/v1/chat/session/${encodeURIComponent(sessionId)}`)
  if (res.code !== 200) {
    const err = new Error(res.message || '删除会话失败')
    err.code = res.code
    err.traceId = res.traceId
    throw err
  }
  return res.data
}

/**
 * 后端健康检查（actuator）
 */
export async function checkHealth() {
  try {
    await http.get('/actuator/health', { timeout: 5000 })
    return true
  } catch {
    return false
  }
}
