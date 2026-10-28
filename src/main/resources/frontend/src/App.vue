<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="brand">
        <span class="logo">◈</span>
        <div>
          <div class="title">shujia</div>
          <div class="subtitle">企业级 RAG 知识库</div>
        </div>
      </div>

      <nav class="nav">
        <button :class="{ active: tab === 'chat' }" @click="tab = 'chat'">
          <span class="ico">💬</span> 智能问答
        </button>
        <button :class="{ active: tab === 'eval' }" @click="tab = 'eval'">
          <span class="ico">📊</span> 检索评测
        </button>
      </nav>

      <!-- 会话列表 -->
      <div class="section sessions">
        <div class="section-title">会话</div>
        <button class="ghost new-session" @click="newSession">+ 新建会话</button>
        <ul class="session-list">
          <li
            v-for="s in sessions"
            :key="s.sessionId"
            class="session-row"
            :class="{ active: s.sessionId === sessionId }"
          >
            <button class="session-item" :title="s.sessionId" @click="switchSession(s.sessionId)">
              <span class="session-title">{{ s.title || s.sessionId }}</span>
              <span class="session-count">{{ s.messageCount }}</span>
            </button>
            <button class="session-del" title="删除会话" @click.stop="removeSession(s.sessionId)">
              ×
            </button>
          </li>
          <li v-if="sessions.length === 0" class="session-empty">暂无历史会话</li>
        </ul>
      </div>

      <!-- 会话配置 -->
      <div class="section">
        <div class="section-title">会话配置</div>
        <label class="field">
          <span>召回条数 TopK</span>
          <div class="slider-row">
            <input v-model.number="topk" type="range" min="1" max="10" />
            <b>{{ topk }}</b>
          </div>
        </label>
        <label class="field">
          <span>会话 ID</span>
          <input class="sid" :value="sessionId || '（未选择）'" readonly />
        </label>
        <button class="ghost danger" @click="clearMemory">清空记忆</button>
      </div>

      <!-- 后端状态 -->
      <div class="status" :class="{ ok: online }">
        <span class="dot"></span>
        <span>{{ online === null ? '检测中…' : online ? '后端在线' : '后端离线' }}</span>
        <button class="refresh" @click="probe" title="重新检测">↻</button>
      </div>
    </aside>

    <!-- 主区域 -->
    <main class="main">
      <ChatPanel
        v-show="tab === 'chat'"
        ref="chatRef"
        :session-id="sessionId"
        :topk="topk"
        @pick="onPick"
        @session="onSession"
        @done="loadSessions"
      />
      <EvalPanel v-show="tab === 'eval'" />
    </main>

    <!-- 全局提示 -->
    <Transition name="toast">
      <div v-if="toast" class="toast" :class="toast.type">{{ toast.text }}</div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import ChatPanel from './components/ChatPanel.vue'
import EvalPanel from './components/EvalPanel.vue'
import {
  cleanMemory as cleanMemoryApi,
  checkHealth,
  createSession,
  listSessions,
  fetchHistory,
  deleteSession
} from './api'

const tab = ref('chat')
const topk = ref(3)
// sessionId 只由后端颁发，前端不再生成任何 id
const sessionId = ref('')
const sessions = ref([])
const online = ref(null)
const toast = ref(null)
const chatRef = ref(null)

function showToast(text, type = 'info') {
  toast.value = { text, type }
  setTimeout(() => (toast.value = null), 2600)
}

async function probe() {
  online.value = await checkHealth()
}

async function loadSessions() {
  try {
    sessions.value = await listSessions()
  } catch (err) {
    sessions.value = []
    showToast(err.message || '获取会话列表失败', 'err')
  }
}

// 新建会话：向后端领 id；失败就保持原样，不伪造 id
async function newSession() {
  try {
    sessionId.value = await createSession()
    chatRef.value?.clear()
    await loadSessions()
    showToast('已开启新会话')
  } catch (err) {
    showToast(err.message || '新建会话失败，请稍后重试', 'err')
  }
}

// 切换会话：先清空界面，再从 PG/Redis 拉历史回填
async function switchSession(sid) {
  if (!sid) return
  sessionId.value = sid
  chatRef.value?.clear()
  try {
    const history = await fetchHistory(sid)
    chatRef.value?.loadHistory(history)
  } catch (err) {
    showToast(err.message || '获取历史失败', 'err')
  }
}

async function removeSession(sid) {
  try {
    await deleteSession(sid)
    if (sid === sessionId.value) {
      sessionId.value = ''
      chatRef.value?.clear()
    }
    await loadSessions()
    showToast('会话已删除', 'ok')
  } catch (err) {
    showToast(err.message || '删除会话失败', 'err')
  }
}

function onPick(question) {
  chatRef.value?.fillQuestion(question)
}

// 后端未收到 sessionId 时会自行分配，这里同步过来保持侧边栏与后端一致
function onSession(sid) {
  if (sid && sid !== sessionId.value) {
    sessionId.value = sid
    loadSessions()
  }
}

async function clearMemory() {
  try {
    // 后端 clear() 会同时删 PG 历史、Redis 记忆和该会话的答案缓存
    await cleanMemoryApi(sessionId.value)
    chatRef.value?.clear()
    showToast('会话记忆已清空', 'ok')
  } catch (err) {
    chatRef.value?.clear()
    showToast(err.message || '后端清空失败，已在本地重置', 'err')
  }
}

onMounted(async () => {
  await probe()
  await loadSessions()
})
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
}

.sidebar {
  width: 248px;
  flex-shrink: 0;
  background: var(--bg-panel);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 18px 16px;
  gap: 22px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.logo {
  font-size: 26px;
  color: var(--primary);
}

.title {
  font-size: 15px;
  font-weight: 600;
}

.subtitle {
  font-size: 11.5px;
  color: var(--text-dim);
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav button {
  display: flex;
  align-items: center;
  gap: 10px;
  background: transparent;
  color: var(--text-dim);
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13.5px;
  text-align: left;
  transition: 0.15s;
}

.nav button:hover {
  background: var(--bg-elevated);
  color: var(--text);
}

.nav button.active {
  background: var(--bg-elevated);
  color: var(--text);
  box-shadow: inset 2px 0 0 var(--primary);
}

.ico {
  font-size: 15px;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.section-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  color: var(--text-dim);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  font-size: 12.5px;
  color: var(--text-dim);
}

.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slider-row input[type='range'] {
  flex: 1;
  accent-color: var(--primary);
}

.slider-row b {
  color: var(--text);
  width: 16px;
  text-align: right;
}

.field .sid {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-dim);
  padding: 7px 9px;
  font-size: 12px;
  font-family: Consolas, monospace;
}

.ghost {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-dim);
  border-radius: 7px;
  padding: 8px 12px;
  font-size: 13px;
  transition: 0.15s;
}

.ghost:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.ghost.danger:hover {
  border-color: var(--danger);
  color: var(--danger);
}

.sessions {
  min-height: 0;
  flex: 1;
}

.new-session {
  text-align: left;
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.session-row {
  display: flex;
  align-items: center;
  border-radius: 7px;
  transition: 0.15s;
}

.session-row:hover {
  background: var(--bg-elevated);
}

.session-row.active {
  background: var(--bg-elevated);
  box-shadow: inset 2px 0 0 var(--primary);
}

.session-item {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  color: var(--text-dim);
  padding: 8px 10px;
  font-size: 12.5px;
  text-align: left;
}

.session-row.active .session-item {
  color: var(--text);
}

.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-dim);
  font-family: Consolas, monospace;
}

.session-del {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-right: 6px;
  border-radius: 5px;
  background: transparent;
  color: var(--text-dim);
  font-size: 15px;
  line-height: 1;
  opacity: 0;
  transition: 0.15s;
}

.session-row:hover .session-del {
  opacity: 1;
}

.session-del:hover {
  background: var(--danger);
  color: #fff;
}

.session-empty {
  padding: 10px;
  font-size: 12px;
  color: var(--text-dim);
}

.status {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-dim);
  padding: 10px 12px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.status .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--danger);
}

.status.ok .dot {
  background: var(--success);
  box-shadow: 0 0 8px var(--success);
}

.refresh {
  margin-left: auto;
  background: transparent;
  color: var(--text-dim);
  font-size: 14px;
}

.refresh:hover {
  color: var(--primary);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

.toast {
  position: fixed;
  bottom: 26px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 13.5px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
}

.toast.ok {
  border-color: var(--success);
  color: var(--success);
}

.toast.err {
  border-color: var(--danger);
  color: var(--danger);
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, 12px);
}
</style>
