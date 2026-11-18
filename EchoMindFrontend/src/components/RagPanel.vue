<template>
  <section class="rag-panel">
    <div class="rag-heading">
      <div>
        <span class="kicker">Versioned retrieval</span>
        <h2>企业知识检索</h2>
        <p>MySQL 词法召回与本地语义召回并行，经 RRF、Rerank 后返回可追溯引用。</p>
      </div>
      <div class="rag-stats">
        <strong>{{ stats.published_chunks ?? '-' }}</strong>
        <span>active chunks</span>
      </div>
    </div>

    <div class="rag-grid">
      <section class="workspace-card">
        <div class="card-heading">
          <div><span class="kicker">Hybrid search</span><h3>检索与引用</h3></div>
          <code>RRF + rerank</code>
        </div>
        <div class="search-line">
          <input v-model="query" placeholder="例如：企业版续费后席位多久生效" @keydown.enter="search" />
          <button :disabled="searching || !query.trim()" @click="search">
            {{ searching ? '检索中...' : '检索' }}
          </button>
        </div>
        <div v-if="ragError" class="rag-error" role="alert">{{ ragError }}</div>
        <div v-if="result.degradedModes.length" class="degraded-strip">
          Degraded: {{ result.degradedModes.join(' · ') }}
        </div>

        <div v-if="result.hits.length" class="rag-results">
          <article v-for="(hit, index) in result.hits" :key="hit.chunk_id" class="rag-hit">
            <div class="rag-hit-head">
              <span class="citation-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <strong>{{ hit.document_title }}</strong>
                <small>v{{ hit.version_number }} · chunk {{ hit.chunk_index }}</small>
              </div>
              <b>{{ hit.rerank_score.toFixed(3) }}</b>
            </div>
            <p>{{ hit.content }}</p>
            <div class="score-row">
              <span>lexical {{ hit.lexicalScore.toFixed(3) }}</span>
              <span>vector {{ hit.vectorScore.toFixed(3) }}</span>
              <span>RRF {{ hit.rrfScore.toFixed(4) }}</span>
            </div>
          </article>
        </div>
        <div v-else-if="searched" class="workspace-empty">没有达到证据阈值的切片。</div>

        <div v-if="result.citations.length" class="citation-section">
          <div class="card-heading">
            <div><span class="kicker">Citation chain</span><h3>引用来源</h3></div>
            <span>{{ result.latencyMs }} ms</span>
          </div>
          <ol>
            <li v-for="citation in result.citations" :key="citation.citation_id">
              <strong>{{ citationLabel(citation) }}</strong>
              <p>{{ citation.excerpt }}</p>
              <small>
                chars {{ citation.char_start }}–{{ citation.char_end }}
                · score {{ Number(citation.score).toFixed(3) }}
              </small>
            </li>
          </ol>
        </div>
      </section>

      <section v-if="canManage" class="workspace-card">
        <div class="card-heading">
          <div><span class="kicker">Ingestion</span><h3>文档入库</h3></div>
          <span class="state-chip">{{ currentVersion?.status || 'NEW' }}</span>
        </div>
        <div class="rag-form">
          <label v-if="!documentId">
            <span>文档标题</span>
            <input v-model="title" maxlength="255" placeholder="企业版续费政策" />
          </label>
          <label>
            <span>Markdown / 文本内容</span>
            <textarea v-model="content" rows="8" maxlength="2000000" placeholder="# 续费规则&#10;&#10;续费后五分钟内更新席位。"></textarea>
          </label>
          <div class="rag-inline">
            <label>
              <span>可见范围</span>
              <select v-model="visibility">
                <option value="TENANT">租户内可见</option>
                <option value="CUSTOMER">指定客户</option>
              </select>
            </label>
            <label v-if="visibility === 'CUSTOMER'">
              <span>客户 ID</span>
              <input v-model="customerId" placeholder="customer-001" />
            </label>
          </div>
          <label class="upload-button">
            读取文本文件
            <input type="file" accept=".md,.txt,.json" @change="readFile" />
          </label>
          <div class="side-actions">
            <button :disabled="ingesting || !canSubmit" @click="ingest">
              {{ documentId ? '创建新版本' : '创建并索引' }}
            </button>
            <button
              v-if="currentVersion && currentVersion.status === 'DRAFT'"
              :disabled="publishing"
              @click="publish"
            >
              {{ publishing ? '发布中...' : `发布 v${currentVersion.version_number}` }}
            </button>
          </div>
          <p v-if="documentId" class="scope-note">
            Document {{ documentId }} · version {{ currentVersion?.version_number || '-' }}
          </p>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { backendMeta, requestJson } from '../lib/backends.js'
import {
  canManageKnowledge,
  citationLabel,
  createRagClient,
  normalizeRagSearch
} from '../lib/rag.js'

const props = defineProps({
  settings: { type: Object, required: true },
  accessToken: { type: String, default: '' },
  roles: { type: Array, default: () => [] },
  customerId: { type: String, default: '' }
})

const client = computed(() => {
  const baseUrl = backendMeta('java', props.settings).baseUrl
  return createRagClient((path, options) =>
    requestJson('java', baseUrl, path, options))
})

const canManage = computed(() => Boolean(props.accessToken)
  && canManageKnowledge(props.roles))
const query = ref('企业版续费后席位多久生效')
const title = ref('企业版续费政策')
const content = ref('# 续费规则\n\n续费后五分钟内更新席位和权益。')
const visibility = ref('TENANT')
const customerId = ref(props.customerId || '')
const documentId = ref('')
const currentVersion = ref(null)
const result = reactive(normalizeRagSearch({}))
const stats = ref({ published_chunks: '-' })
const ragError = ref('')
const searching = ref(false)
const ingesting = ref(false)
const publishing = ref(false)
const searched = ref(false)
const canSubmit = computed(() => Boolean(content.value.trim())
  && (documentId.value || title.value.trim())
  && (visibility.value !== 'CUSTOMER' || customerId.value.trim()))

onMounted(loadStats)

async function search() {
  if (!query.value.trim() || searching.value) return
  searching.value = true
  ragError.value = ''
  searched.value = true
  try {
    Object.assign(result, normalizeRagSearch(await client.value.search(query.value.trim(), 5)))
  } catch (error) {
    ragError.value = error.message
  } finally {
    searching.value = false
  }
}

async function ingest() {
  if (!canSubmit.value || ingesting.value) return
  ingesting.value = true
  ragError.value = ''
  try {
    if (!documentId.value) {
      const response = await client.value.createDocument({
        title: title.value.trim(),
        content: content.value,
        visibility: visibility.value,
        customerId: customerId.value.trim()
      })
      documentId.value = response.document_id
      currentVersion.value = response.version
      title.value = response.title
    } else {
      currentVersion.value = await client.value.addVersion(documentId.value, {
        content: content.value,
        visibility: visibility.value,
        customerId: customerId.value.trim()
      })
    }
  } catch (error) {
    ragError.value = error.message
  } finally {
    ingesting.value = false
  }
}

async function publish() {
  if (!documentId.value || !currentVersion.value || publishing.value) return
  publishing.value = true
  ragError.value = ''
  try {
    currentVersion.value = await client.value.publish(
      documentId.value,
      currentVersion.value.version_number
    )
    await loadStats()
  } catch (error) {
    ragError.value = error.message
  } finally {
    publishing.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await client.value.stats()
  } catch {
    stats.value = { published_chunks: '-' }
  }
}

async function readFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  content.value = await file.text()
  if (!documentId.value) title.value = file.name.replace(/\.[^.]+$/, '')
}
</script>

<style scoped>
.rag-panel { display: grid; gap: 16px; }
.rag-heading, .card-heading, .rag-hit-head, .side-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.rag-heading h2 { margin: 4px 0 6px; }
.rag-heading p { margin: 0; color: var(--text-soft); }
.rag-stats { min-width: 130px; padding: 12px 16px; border: 1px solid var(--line); border-radius: 14px; background: var(--panel); }
.rag-stats strong, .rag-stats span { display: block; }
.rag-stats strong { font-size: 22px; }
.rag-stats span { color: var(--muted); font-size: 11px; }
.rag-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(320px, .65fr); gap: 16px; }
.rag-form { display: grid; gap: 12px; }
.rag-form label, .rag-inline label { display: grid; gap: 6px; }
.rag-inline { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.rag-inline label > span, .rag-form label > span { color: var(--text-soft); font-size: 12px; }
select { min-height: 42px; padding: 0 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); }
.rag-results { display: grid; gap: 10px; margin-top: 14px; }
.rag-hit { padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel-2); }
.rag-hit-head { justify-content: flex-start; }
.rag-hit-head > div { flex: 1; }
.rag-hit-head strong, .rag-hit-head small { display: block; }
.rag-hit-head small, .score-row, .citation-section small { color: var(--muted); font-size: 11px; }
.rag-hit-head b { color: #cdd1ff; }
.citation-index { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 9px; background: var(--accent-soft); color: #d9dcff; font-size: 11px; font-weight: 800; }
.rag-hit p { line-height: 1.65; color: var(--text-soft); }
.score-row { display: flex; flex-wrap: wrap; gap: 12px; }
.citation-section { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--line); }
.citation-section ol { display: grid; gap: 10px; margin: 0; padding-left: 22px; }
.citation-section li { padding: 10px 12px; border-radius: 10px; background: var(--panel-2); }
.citation-section p { margin: 6px 0; color: var(--text-soft); line-height: 1.55; }
.degraded-strip, .rag-error { margin-top: 12px; padding: 10px 12px; border-radius: 10px; background: rgba(243, 185, 75, .12); color: #ffda91; font-size: 12px; }
.rag-error { background: var(--red-soft); color: #ffc7cc; }
.state-chip { padding: 5px 9px; border-radius: 999px; background: var(--accent-soft); color: #cdd1ff; font-size: 11px; font-weight: 800; }
@media (max-width: 900px) {
  .rag-grid, .rag-inline { grid-template-columns: 1fr; }
  .rag-heading { flex-direction: column; align-items: flex-start; }
}
</style>
