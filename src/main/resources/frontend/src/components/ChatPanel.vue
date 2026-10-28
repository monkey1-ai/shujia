<template>
  <div class="chat-panel">
    <div ref="scrollRef" class="messages">
      <!-- 空态引导 -->
      <div v-if="messages.length === 0" class="empty">
        <div class="empty-icon">◈</div>
        <h2>shujia · 企业级 RAG 知识库</h2>
        <p>基于向量检索 + 重排 + 大模型生成，回答会引用知识库原文</p>
        <div class="samples">
          <button
            v-for="s in samples"
            :key="s"
            class="sample"
            @click="$emit('pick', s)"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <!-- 消息列表 -->
      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="message"
        :class="msg.role"
      >
        <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble-wrap">
          <div class="bubble">
            <div v-if="msg.error" class="error-text">⚠ {{ msg.content }}</div>
            <div v-else class="content" v-html="render(msg.content)"></div>
            <span v-if="msg.loading" class="typing"><i></i><i></i><i></i></span>
            <!-- 流式已开始输出时，用光标表示还在继续 -->
            <span v-else-if="msg.streaming" class="caret"></span>
          </div>
          <!-- 元信息：耗时、缓存标记、追踪号、复制 -->
          <div v-if="msg.role === 'assistant' && !msg.loading" class="meta">
            <span v-if="msg.cached" class="tag cached">⚡ 缓存命中</span>
            <span v-if="msg.costMs != null" class="tag">⏱ {{ msg.costMs }} ms</span>
            <span v-if="msg.traceId" class="tag trace" :title="msg.traceId">
              🔗 {{ msg.traceId.slice(0, 8) }}
            </span>
            <button class="copy" @click="copy(msg.content)">
              {{ copiedIndex === i ? '已复制' : '复制' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <textarea
        v-model="draft"
        placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
        rows="1"
        :disabled="loading"
        @keydown="onKeydown"
        @input="autoGrow"
        ref="textareaRef"
      ></textarea>
      <button v-if="loading" class="send stop" @click="stop">停止</button>
      <button v-else class="send" :disabled="!draft.trim()" @click="send">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, watch, onBeforeUnmount } from 'vue'
import { ragSearchStream } from '../api'

const props = defineProps({
  sessionId: { type: String, required: true },
  topk: { type: Number, default: 3 }
})
const emit = defineEmits(['pick', 'session', 'done'])

const messages = ref([])
const draft = ref('')
const loading = ref(false)
const scrollRef = ref(null)
const textareaRef = ref(null)
const copiedIndex = ref(-1)
let controller = null

const samples = [
  '什么是RAG？',
  'PostgreSQL如何支持向量检索？',
  '为什么需要重排模型？',
  'RAG如何解决大模型幻觉？'
]

// 轻量 Markdown 渲染：转义 HTML，再处理代码块/加粗/行内代码/换行
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function render(text) {
  if (!text) return ''
  let html = escapeHtml(text)
  html = html.replace(/```([\s\S]*?)```/g, (_, code) => `<pre><code>${code.trim()}</code></pre>`)
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/\n/g, '<br>')
  return html
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(messages, scrollToBottom, { deep: true })

function autoGrow() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

async function send() {
  const question = draft.value.trim()
  if (!question || loading.value) return

  // 每条消息一个 id：同一次发送若被重试，后端靠它判重，不会写两条
  const messageId = genMessageId()

  messages.value.push({ role: 'user', content: question })
  draft.value = ''
  await nextTick()
  if (textareaRef.value) textareaRef.value.style.height = 'auto'

  // 必须建响应式对象后再 push：push 进去的是代理，持有原始对象直接改属性不会触发重渲染
  const botMsg = reactive({
    role: 'assistant',
    content: '',
    loading: true,
    streaming: false,
    costMs: null,
    cached: false,
    traceId: ''
  })
  messages.value.push(botMsg)
  loading.value = true
  controller = new AbortController()

  try {
    await ragSearchStream(
      { question, topk: props.topk, sessionId: props.sessionId, messageId },
      {
        signal: controller.signal,
        // 后端可能新分配 sessionId（前端未带时），回传给父组件保持同步
        onSession: (sid) => emit('session', sid),
        onContent: (chunk) => {
          botMsg.content += chunk
          botMsg.streaming = true
          scrollToBottom()
        },
        onDone: ({ costMs, cached }) => {
          botMsg.costMs = costMs
          botMsg.cached = cached
        }
      }
    )
  } catch (err) {
    if (err.name === 'AbortError') {
      // 用户主动停止：保留已生成的内容，不当作错误
      if (!botMsg.content) botMsg.content = '（已停止生成）'
    } else {
      botMsg.error = true
      botMsg.content = err.message || '请求失败'
      botMsg.traceId = err.traceId || ''
    }
  } finally {
    botMsg.streaming = false
    botMsg.loading = false
    loading.value = false
    controller = null
    // 通知父组件刷新会话列表（标题/消息数/活跃时间会变）
    emit('done')
  }
}

// crypto.randomUUID 需要安全上下文（localhost/https）；不可用时退化
function genMessageId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  return Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

function stop() {
  controller?.abort()
}

onBeforeUnmount(() => controller?.abort())

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    copiedIndex.value = messages.value.findIndex((m) => m.content === text)
    setTimeout(() => (copiedIndex.value = -1), 1500)
  } catch {
    /* 剪贴板不可用时静默忽略 */
  }
}

// 供父组件从侧边栏示例注入问题
function fillQuestion(q) {
  draft.value = q
  send()
}

/**
 * 用后端历史回填消息列表（切换/恢复会话时调用）
 * @param {string[]} history 形如 ["user:xxx", "assistant:yyy", ...]
 */
function loadHistory(history) {
  messages.value = (history || []).map((raw) => {
    const idx = raw.indexOf(':')
    const role = idx < 0 ? 'user' : raw.slice(0, idx)
    const content = idx < 0 ? raw : raw.slice(idx + 1)
    return {
      role: role === 'assistant' ? 'assistant' : 'user',
      content,
      loading: false,
      streaming: false,
      costMs: null,
      cached: false,
      traceId: ''
    }
  })
  scrollToBottom()
}

defineExpose({ fillQuestion, loadHistory, clear: () => (messages.value = []) })
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.empty {
  margin: auto;
  text-align: center;
  max-width: 520px;
}

.empty-icon {
  font-size: 42px;
  color: var(--primary);
  margin-bottom: 12px;
}

.empty h2 {
  font-size: 20px;
  margin-bottom: 8px;
}

.empty p {
  color: var(--text-dim);
  margin-bottom: 22px;
}

.samples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.sample {
  background: var(--bg-elevated);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 13px;
  transition: 0.15s;
}

.sample:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.message {
  display: flex;
  gap: 12px;
  max-width: 860px;
}

.message.user {
  flex-direction: row-reverse;
  align-self: flex-end;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.message.user .avatar {
  background: var(--primary);
  color: #fff;
}

.message.assistant .avatar {
  background: var(--bg-elevated);
  color: var(--success);
  border: 1px solid var(--border);
}

.bubble-wrap {
  min-width: 0;
  max-width: 100%;
}

.bubble {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 16px;
  word-break: break-word;
}

.message.user .bubble {
  background: var(--primary-dim);
  border-color: transparent;
}

.content :deep(pre) {
  background: #0b0f14;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
}

.content :deep(code) {
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 12.5px;
}

.content :deep(p:not(:last-child)) {
  margin-bottom: 6px;
}

.error-text {
  color: var(--danger);
}

.typing {
  display: inline-flex;
  gap: 3px;
  margin-left: 4px;
}

.typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-dim);
  animation: blink 1.2s infinite;
}

.typing i:nth-child(2) {
  animation-delay: 0.2s;
}

.typing i:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 60%, 100% { opacity: 0.25; }
  30% { opacity: 1; }
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  padding-left: 2px;
}

.tag {
  font-size: 11.5px;
  color: var(--text-dim);
}

.tag.trace {
  font-family: Consolas, monospace;
  cursor: help;
}

.tag.cached {
  color: var(--warning);
}

.caret {
  display: inline-block;
  width: 2px;
  height: 14px;
  background: var(--primary);
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: caret-blink 1s step-end infinite;
}

@keyframes caret-blink {
  50% { opacity: 0; }
}

.send.stop {
  background: var(--danger);
}

.send.stop:hover:not(:disabled) {
  background: #d1382f;
}

.copy {
  background: transparent;
  color: var(--text-dim);
  font-size: 11.5px;
  padding: 0;
}

.copy:hover {
  color: var(--primary);
}

.input-area {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 16px 28px 20px;
  border-top: 1px solid var(--border);
  background: var(--bg-panel);
}

.input-area textarea {
  flex: 1;
  resize: none;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text);
  padding: 11px 14px;
  font-size: 14px;
  line-height: 1.5;
  max-height: 160px;
  transition: border-color 0.15s;
}

.input-area textarea:focus {
  border-color: var(--primary);
}

.send {
  background: var(--primary);
  color: #fff;
  border-radius: var(--radius);
  padding: 11px 22px;
  font-size: 14px;
  font-weight: 500;
  transition: 0.15s;
  white-space: nowrap;
}

.send:hover:not(:disabled) {
  background: #2563eb;
}
</style>
