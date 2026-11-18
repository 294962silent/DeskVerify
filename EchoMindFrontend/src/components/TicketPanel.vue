<template>
  <section class="ticket-workspace">
    <header class="ticket-heading">
      <div>
        <span class="kicker">Human support</span>
        <h1>工单工作台</h1>
        <p>订单与订阅问题可转人工处理。租户和客户范围来自当前登录会话。</p>
      </div>
      <div class="session-scope">
        <span>客户上下文</span>
        <strong>{{ customerId || '未绑定' }}</strong>
        <small>{{ accessToken ? 'JWT 会话已连接' : '缺少 Java 访问令牌' }}</small>
      </div>
    </header>

    <section v-if="error" class="ticket-alert" role="alert">
      <div>
        <strong>{{ conflict ? '工单状态已变化' : '工单请求失败' }}</strong>
        <p>{{ error }}</p>
      </div>
      <button v-if="conflict && currentTicket" class="quiet-button" @click="refreshTicket">
        刷新工单
      </button>
    </section>

    <div class="ticket-grid">
      <section class="ticket-card create-card">
        <div class="card-head">
          <div><span class="kicker">Customer intake</span><h2>创建工单</h2></div>
          <span class="state-chip">QUEUED</span>
        </div>
        <div v-if="canCreate" class="ticket-form">
          <label>
            <span>问题标题</span>
            <input v-model="form.subject" maxlength="255" placeholder="例如：订阅续费后权限未生效" />
          </label>
          <label>
            <span>问题描述</span>
            <textarea v-model="form.description" rows="5" maxlength="20000" placeholder="描述现象、订单号或已经尝试过的排查步骤"></textarea>
          </label>
          <label>
            <span>优先级</span>
            <select v-model="form.priority">
              <option value="LOW">低</option>
              <option value="NORMAL">普通</option>
              <option value="HIGH">高</option>
              <option value="URGENT">紧急</option>
            </select>
          </label>
          <button :disabled="submitting || !form.subject.trim() || !form.description.trim()" @click="createTicket">
            {{ submitting ? '提交中...' : '提交工单' }}
          </button>
        </div>
        <div v-else class="ticket-empty">
          <strong>当前会话不能创建工单</strong>
          <p>需要 Java Bearer token，且宿主必须提供不可编辑的客户上下文。</p>
        </div>
      </section>

      <section class="ticket-card lookup-card">
        <div class="card-head">
          <div><span class="kicker">Case lookup</span><h2>查看工单</h2></div>
          <span class="state-chip">tenant scoped</span>
        </div>
        <div class="lookup-line">
          <input v-model="lookupId" placeholder="输入 ticket ID" @keydown.enter="lookupTicket" />
          <button :disabled="loading || !lookupId.trim()" @click="lookupTicket">查看</button>
        </div>
        <p class="scope-note">未知工单与跨租户工单均返回相同的 404，不泄露工单归属。</p>

        <div v-if="canList" class="queue-tools">
          <div class="lookup-line">
            <select v-model="listFilter.status">
              <option value="">全部状态</option>
              <option v-for="status in statuses" :key="status" :value="status">{{ status }}</option>
            </select>
            <button class="quiet-button" :disabled="loading" @click="loadQueue">刷新队列</button>
          </div>
          <div v-if="queue.length" class="queue-list">
            <button v-for="item in queue" :key="item.ticket_id" class="queue-item" @click="selectTicket(item)">
              <span>{{ item.subject }}</span>
              <small>{{ item.status }} · v{{ item.version }}</small>
            </button>
          </div>
          <p v-else class="scope-note">当前筛选条件下没有工单。</p>
        </div>
      </section>
    </div>

    <section v-if="currentTicket" class="ticket-card detail-card">
      <div class="detail-head">
        <div>
          <span class="kicker">{{ currentTicket.ticket_id }}</span>
          <h2>{{ currentTicket.subject }}</h2>
          <p>{{ currentTicket.description }}</p>
        </div>
        <div class="ticket-state-block">
          <span :class="['ticket-status', `status-${currentTicket.status.toLowerCase()}`]">
            {{ currentTicket.status }}
          </span>
          <small>SLA {{ formatDate(currentTicket.sla_due_at) }}</small>
        </div>
      </div>

      <div class="ticket-facts">
        <div><span>客户</span><strong>{{ currentTicket.customer_id }}</strong></div>
        <div><span>负责人</span><strong>{{ currentTicket.assignee_user_id || '待认领' }}</strong></div>
        <div><span>优先级</span><strong>{{ currentTicket.priority }}</strong></div>
        <div><span>版本</span><strong>v{{ currentTicket.version }}</strong></div>
      </div>

      <div v-if="canUseSupportActions" class="support-actions">
        <button
          v-if="['OPEN', 'QUEUED', 'REOPENED'].includes(currentTicket.status)"
          :disabled="acting"
          @click="runAction('claim')"
        >
          认领
        </button>
        <button v-if="currentTicket.status === 'CLAIMED'" :disabled="acting" @click="runAction('resolve')">
          标记解决
        </button>
        <button v-if="currentTicket.status === 'RESOLVED'" :disabled="acting" @click="runAction('close')">
          关闭工单
        </button>
        <button
          v-if="['RESOLVED', 'CLOSED'].includes(currentTicket.status)"
          class="quiet-button"
          :disabled="acting"
          @click="runAction('reopen')"
        >
          重新打开
        </button>
      </div>

      <div v-else-if="currentTicket.status === 'RESOLVED' || currentTicket.status === 'CLOSED'" class="support-actions">
        <button class="quiet-button" :disabled="acting" @click="runAction('reopen')">问题仍未解决，重新打开</button>
      </div>

      <div v-if="currentTicket.status === 'CLAIMED' || currentTicket.status === 'RESOLVED'" class="action-fields">
        <label v-if="currentTicket.status === 'CLAIMED' && canUseSupportActions">
          <span>解决方案</span>
          <textarea v-model="resolutionDraft" rows="3" placeholder="说明已执行的修复和客户可见结果"></textarea>
        </label>
        <label v-if="['RESOLVED', 'CLOSED'].includes(currentTicket.status)">
          <span>重新打开原因</span>
          <textarea v-model="reopenDraft" rows="3" placeholder="说明问题为何仍然存在"></textarea>
        </label>
      </div>

      <section class="event-section">
        <div class="card-head">
          <div><span class="kicker">Immutable history</span><h2>事件历史</h2></div>
          <button class="link-button" @click="loadEvents(currentTicket.ticket_id)">刷新</button>
        </div>
        <ol v-if="events.length" class="event-list">
          <li v-for="event in events" :key="event.event_id">
            <span class="event-mark"></span>
            <div>
              <strong>{{ event.event_type }}</strong>
              <p>
                {{ event.from_status || 'NEW' }} → {{ event.to_status || '—' }}
                · {{ event.actor_user_id }}
              </p>
              <small>{{ formatDate(event.created_at) }}</small>
            </div>
          </li>
        </ol>
        <p v-else class="scope-note">尚未加载事件历史。</p>
      </section>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { backendMeta, requestJson } from '../lib/backends.js'
import {
  TicketSubmission,
  buildActionPayload,
  canCreateTicket,
  createTicketClient,
  normalizeTicketError
} from '../lib/tickets.js'

const props = defineProps({
  settings: { type: Object, required: true },
  accessToken: { type: String, default: '' },
  customerId: { type: String, default: '' },
  roles: { type: Array, default: () => [] }
})

const statuses = ['OPEN', 'QUEUED', 'CLAIMED', 'RESOLVED', 'CLOSED', 'REOPENED']
const form = reactive({ subject: '', description: '', priority: 'NORMAL' })
const lookupId = ref('')
const queue = ref([])
const currentTicket = ref(null)
const events = ref([])
const error = ref('')
const conflict = ref(false)
const loading = ref(false)
const submitting = ref(false)
const acting = ref(false)
const resolutionDraft = ref('')
const reopenDraft = ref('')
const listFilter = reactive({ status: '' })
const submission = new TicketSubmission()

const normalizedRoles = computed(() => new Set(
  props.roles.map((role) => String(role || '').trim().toUpperCase()).filter(Boolean)
))
const canCreate = computed(() => canCreateTicket(props.accessToken, props.customerId))
const canList = computed(() => normalizedRoles.value.has('SUPPORT_AGENT')
  || normalizedRoles.value.has('TENANT_ADMIN'))
const canUseSupportActions = computed(() => canList.value)

const client = computed(() => {
  const baseUrl = backendMeta('java', props.settings).baseUrl
  return createTicketClient((path, options) =>
    requestJson('java', baseUrl, path, options))
})

onMounted(() => {
  if (canList.value) loadQueue()
})

async function createTicket() {
  if (!canCreate.value || submitting.value) return
  clearError()
  submitting.value = true
  try {
    const result = await submission.submit(client.value, {
      customer_id: props.customerId,
      subject: form.subject.trim(),
      description: form.description.trim(),
      priority: form.priority,
      queue_name: form.priority === 'URGENT' ? 'urgent-support' : 'support-default'
    })
    if (result.duplicate) return
    currentTicket.value = result.ticket
    lookupId.value = result.ticket.ticket_id
    form.subject = ''
    form.description = ''
    await loadEvents(result.ticket.ticket_id)
    if (canList.value) await loadQueue()
  } catch (caught) {
    applyError(caught)
  } finally {
    submitting.value = false
  }
}

async function lookupTicket() {
  const ticketId = lookupId.value.trim()
  if (!ticketId) return
  loading.value = true
  clearError()
  try {
    currentTicket.value = await client.value.get(ticketId)
    resolutionDraft.value = currentTicket.value.resolution || ''
    reopenDraft.value = ''
    await loadEvents(ticketId)
  } catch (caught) {
    currentTicket.value = null
    events.value = []
    applyError(caught)
  } finally {
    loading.value = false
  }
}

async function loadQueue() {
  if (!canList.value) return
  loading.value = true
  try {
    queue.value = await client.value.list({
      status: listFilter.status,
      limit: 20
    })
  } catch (caught) {
    applyError(caught)
  } finally {
    loading.value = false
  }
}

function selectTicket(ticket) {
  currentTicket.value = ticket
  lookupId.value = ticket.ticket_id
  resolutionDraft.value = ticket.resolution || ''
  reopenDraft.value = ''
  clearError()
  loadEvents(ticket.ticket_id)
}

async function runAction(action) {
  if (!currentTicket.value || acting.value) return
  clearError()
  const payload = buildActionPayload(
    action,
    currentTicket.value,
    action === 'resolve' ? resolutionDraft.value : reopenDraft.value
  )
  if ((action === 'resolve' && !payload.resolution)
      || (action === 'reopen' && !payload.reason)) {
    error.value = action === 'resolve' ? '请填写解决方案。' : '请填写重新打开原因。'
    return
  }
  acting.value = true
  try {
    const ticketId = currentTicket.value.ticket_id
    const next = await client.value[action](
      ticketId,
      payload.expected_version,
      action === 'resolve' ? payload.resolution : payload.reason
    )
    currentTicket.value = next
    await loadEvents(ticketId)
    if (canList.value) await loadQueue()
  } catch (caught) {
    applyError(caught)
  } finally {
    acting.value = false
  }
}

async function refreshTicket() {
  if (!currentTicket.value) return
  lookupId.value = currentTicket.value.ticket_id
  await lookupTicket()
}

async function loadEvents(ticketId) {
  try {
    events.value = await client.value.events(ticketId)
  } catch (caught) {
    applyError(caught)
  }
}

function applyError(caught) {
  const normalized = normalizeTicketError(caught)
  conflict.value = normalized.code === 'TICKET_STATE_CONFLICT'
  error.value = normalized.status === 404
    ? '未找到该工单，或当前身份无权查看。'
    : normalized.message
}

function clearError() {
  error.value = ''
  conflict.value = false
}

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}
</script>

<style scoped>
.ticket-workspace { display: grid; gap: 18px; max-width: 1180px; margin: 0 auto; padding: 30px; }
.ticket-heading, .detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.ticket-heading h1 { margin: 5px 0 8px; font-size: clamp(28px, 4vw, 42px); }
.ticket-heading p, .detail-head p { margin: 0; color: var(--text-soft); line-height: 1.7; }
.session-scope { min-width: 220px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 14px; background: var(--panel); }
.session-scope span, .session-scope small { display: block; color: var(--muted); font-size: 11px; }
.session-scope strong { display: block; margin: 6px 0; font-size: 16px; }
.ticket-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }
.ticket-card { padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); box-shadow: var(--shadow-low); }
.card-head, .lookup-line, .support-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-head h2, .detail-head h2 { margin: 4px 0 0; }
.card-head { margin-bottom: 16px; }
.state-chip, .ticket-status { padding: 5px 9px; border-radius: 999px; background: var(--accent-soft); color: #cdd1ff; font-size: 11px; font-weight: 800; }
.ticket-form { display: grid; gap: 13px; }
.ticket-form label, .action-fields label { display: grid; gap: 6px; }
.ticket-form label > span, .action-fields label > span { color: var(--text-soft); font-size: 12px; }
select { width: 100%; min-height: 42px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); }
.lookup-line input, .lookup-line select { flex: 1; }
.scope-note, .ticket-empty p { margin: 12px 0 0; color: var(--muted); line-height: 1.65; }
.queue-tools { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--line); }
.queue-list { display: grid; gap: 8px; max-height: 260px; margin-top: 12px; overflow: auto; }
.queue-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; background: var(--panel-2); text-align: left; }
.queue-item small { color: var(--muted); }
.ticket-alert { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 14px 16px; border: 1px solid rgba(241, 107, 118, 0.35); border-radius: 12px; background: var(--red-soft); }
.ticket-alert p { margin: 4px 0 0; color: #ffc7cc; }
.detail-card { display: grid; gap: 20px; }
.ticket-state-block { display: grid; justify-items: end; gap: 6px; color: var(--muted); font-size: 11px; }
.ticket-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.ticket-facts > div { padding: 12px; border-radius: 12px; background: var(--panel-2); }
.ticket-facts span, .ticket-facts strong { display: block; }
.ticket-facts span { color: var(--muted); font-size: 11px; }
.ticket-facts strong { margin-top: 5px; font-size: 13px; }
.support-actions { justify-content: flex-start; flex-wrap: wrap; }
.action-fields { display: grid; gap: 12px; }
.event-section { padding-top: 18px; border-top: 1px solid var(--line); }
.event-list { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.event-list li { display: grid; grid-template-columns: 16px 1fr; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.event-mark { width: 8px; height: 8px; margin-top: 5px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }
.event-list p { margin: 4px 0; color: var(--text-soft); font-size: 12px; }
.event-list small { color: var(--muted); }
.status-resolved, .status-closed { background: var(--green-soft); color: #b9f5d8; }
.status-claimed { background: rgba(243, 185, 75, 0.16); color: #ffda91; }
@media (max-width: 820px) {
  .ticket-workspace { padding: 18px; }
  .ticket-grid, .ticket-facts { grid-template-columns: 1fr; }
  .ticket-heading, .detail-head { flex-direction: column; }
  .ticket-state-block { justify-items: start; }
}
</style>
