<template>
  <div class="eval-panel">
    <header class="head">
      <div>
        <h2>检索质量评测</h2>
        <p>基于内置评测集 <code>qa_testset.json</code> 跑一遍向量检索，统计命中率与 MRR</p>
      </div>
      <div class="controls">
        <label class="topk">
          <span>TopK</span>
          <input v-model.number="topK" type="number" min="1" max="10" />
        </label>
        <button class="run" :disabled="running" @click="run">
          {{ running ? '评测中…' : '开始评测' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="banner error">⚠ {{ error }}</div>

    <div v-if="result" class="result">
      <!-- 指标卡片 -->
      <div class="cards">
        <div class="card">
          <span class="label">命中率 HitRate</span>
          <strong class="value">{{ (result.hitRate * 100).toFixed(1) }}<small>%</small></strong>
          <div class="bar"><i :style="{ width: result.hitRate * 100 + '%' }"></i></div>
        </div>
        <div class="card">
          <span class="label">MRR 平均倒数排名</span>
          <strong class="value">{{ result.mrr.toFixed(3) }}</strong>
          <div class="bar"><i :style="{ width: Math.min(result.mrr, 1) * 100 + '%' }"></i></div>
        </div>
        <div class="card">
          <span class="label">命中 / 总题数</span>
          <strong class="value">{{ result.hits }}<small>/ {{ result.totalQueries }}</small></strong>
          <span class="hint">命中越靠前，MRR 越接近 1</span>
        </div>
      </div>

      <!-- 明细表 -->
      <div class="details">
        <div class="details-head">
          <h3>逐题明细</h3>
          <span class="count">{{ result.details.length }} 条</span>
        </div>
        <ul>
          <li v-for="(d, i) in parsedDetails" :key="i" :class="{ hit: d.hit }">
            <span class="idx">{{ i + 1 }}</span>
            <span class="dot"></span>
            <div class="detail-body">
              <div class="q">{{ d.question }}</div>
              <div class="a">{{ d.answer }}</div>
            </div>
            <span class="rank" :class="{ hit: d.hit }">
              {{ d.hit ? `排名 ${d.rank}` : '未命中' }}
            </span>
          </li>
        </ul>
      </div>
    </div>

    <div v-else-if="!running" class="placeholder">
      <div class="ph-icon">◎</div>
      <p>点击「开始评测」运行一次检索质量评估</p>
    </div>

    <div v-if="running" class="placeholder">
      <div class="ph-icon spin">◌</div>
      <p>正在逐题检索并计算指标，请稍候…</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { runEval } from '../api'

const topK = ref(3)
const running = ref(false)
const result = ref(null)
const error = ref('')

// 后端 details 格式："问题: q | 答案: a | 是否命中: true | 排名: 2"
const parsedDetails = computed(() => {
  if (!result.value?.details) return []
  return result.value.details.map(parseDetail)
})

function parseDetail(line) {
  const parts = {}
  for (const seg of line.split(' | ')) {
    const idx = seg.indexOf(':')
    if (idx > 0) parts[seg.slice(0, idx).trim()] = seg.slice(idx + 1).trim()
  }
  return {
    question: parts['问题'] || '',
    answer: parts['答案'] || '',
    hit: parts['是否命中'] === 'true',
    rank: parts['排名'] || ''
  }
}

async function run() {
  running.value = true
  error.value = ''
  try {
    result.value = await runEval(topK.value)
  } catch (err) {
    error.value = err.message || '评测失败'
    result.value = null
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.eval-panel {
  height: 100%;
  overflow-y: auto;
  padding: 28px 32px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 24px;
}

.head h2 {
  font-size: 19px;
  margin-bottom: 6px;
}

.head p {
  color: var(--text-dim);
  font-size: 13px;
}

.head code {
  background: var(--bg-elevated);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topk {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-dim);
}

.topk input {
  width: 58px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text);
  padding: 7px 9px;
  font-size: 13px;
}

.run {
  background: var(--primary);
  color: #fff;
  border-radius: 8px;
  padding: 9px 20px;
  font-size: 13.5px;
  font-weight: 500;
}

.run:hover:not(:disabled) {
  background: #2563eb;
}

.banner {
  padding: 11px 14px;
  border-radius: 8px;
  margin-bottom: 18px;
  font-size: 13.5px;
}

.banner.error {
  background: rgba(248, 81, 73, 0.12);
  border: 1px solid rgba(248, 81, 73, 0.3);
  color: var(--danger);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 26px;
}

.card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card .label {
  font-size: 12.5px;
  color: var(--text-dim);
}

.card .value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
}

.card .value small {
  font-size: 14px;
  color: var(--text-dim);
  font-weight: 400;
  margin-left: 3px;
}

.card .hint {
  font-size: 11.5px;
  color: var(--text-dim);
}

.bar {
  height: 5px;
  background: var(--bg-elevated);
  border-radius: 3px;
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--primary), var(--success));
  transition: width 0.5s;
}

.details {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.details-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 18px;
  border-bottom: 1px solid var(--border);
}

.details-head h3 {
  font-size: 14px;
}

.details-head .count {
  font-size: 12px;
  color: var(--text-dim);
}

.details ul {
  list-style: none;
}

.details li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
}

.details li:last-child {
  border-bottom: none;
}

.details li.hit {
  background: rgba(46, 160, 67, 0.05);
}

.idx {
  width: 22px;
  color: var(--text-dim);
  font-size: 12px;
  padding-top: 2px;
  flex-shrink: 0;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--danger);
  margin-top: 8px;
  flex-shrink: 0;
}

li.hit .dot {
  background: var(--success);
}

.detail-body {
  flex: 1;
  min-width: 0;
}

.detail-body .q {
  font-size: 13.5px;
  margin-bottom: 3px;
}

.detail-body .a {
  font-size: 12.5px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.rank {
  font-size: 12px;
  color: var(--danger);
  white-space: nowrap;
  padding-top: 2px;
  flex-shrink: 0;
}

.rank.hit {
  color: var(--success);
}

.placeholder {
  margin-top: 80px;
  text-align: center;
  color: var(--text-dim);
}

.ph-icon {
  font-size: 40px;
  margin-bottom: 12px;
  color: var(--border);
}

.ph-icon.spin {
  animation: rotate 1.4s linear infinite;
  color: var(--primary);
}

@keyframes rotate {
  to { transform: rotate(360deg); }
}
</style>
