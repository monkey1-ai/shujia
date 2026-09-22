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
          <input class="sid" :value="sessionId" readonly />
        </label>
        <button class="ghost" @click="newSession">新建会话</button>
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
import { cleanMemory as cleanMemoryApi, checkHealth } from './api'

const tab = ref('chat')
const topk = ref(3)
const sessionId = ref(genSessionId())
const online = ref(null)
const toast = ref(null)
const chatRef = ref(null)

function genSessionId() {
  return 'web-' + Math.random().toString(36).slice(2, 10)
}

function showToast(text, type = 'info') {
  toast.value = { text, type }
  setTimeout(() => (toast.value = null), 2600)
}

async function probe() {
  online.value = await checkHealth()
}

function newSession() {
  sessionId.value = genSessionId()
  chatRef.value?.clear()
  showToast('已开启新会话')
}

function onPick(question) {
  chatRef.value?.fillQuestion(question)
}

// 后端未收到 sessionId 时会自行分配，这里同步过来保持侧边栏与后端一致
function onSession(sid) {
  if (sid && sid !== sessionId.value) sessionId.value = sid
}

async function clearMemory() {
  try {
    // 注意：后端 /cleanMemory/clean 从单例 QueryRequest 读取 sessionId，拿不到前端传参，
    // 因此这里同时在前端清空消息列表，保证界面上会话被重置。
    await cleanMemoryApi(sessionId.value)
    chatRef.value?.clear()
    showToast('会话记忆已清空', 'ok')
  } catch (err) {
    chatRef.value?.clear()
    showToast(err.message || '后端清空失败，已在本地重置', 'err')
  }
}

onMounted(probe)
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
