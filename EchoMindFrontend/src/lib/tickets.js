export class TicketApiError extends Error {
  constructor(message, { status = 0, code = '', retryable = false, requestId = '' } = {}) {
    super(message)
    this.name = 'TicketApiError'
    this.status = status
    this.code = code
    this.retryable = retryable
    this.requestId = requestId
  }
}

export function createTicketClient(request) {
  if (typeof request !== 'function') {
    throw new TypeError('request must be a function')
  }

  return {
    create(payload, idempotencyKey) {
      return request('/api/v1/tickets', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey
        },
        body: JSON.stringify(payload)
      })
    },
    get(ticketId) {
      return request(`/api/v1/tickets/${encodeURIComponent(ticketId)}`)
    },
    list({ status = '', customerId = '', assigneeUserId = '', limit = 20 } = {}) {
      const params = new URLSearchParams()
      if (status) params.set('status', status)
      if (customerId) params.set('customer_id', customerId)
      if (assigneeUserId) params.set('assignee_user_id', assigneeUserId)
      params.set('limit', String(limit))
      return request(`/api/v1/tickets?${params}`)
    },
    claim(ticketId, expectedVersion) {
      return actionRequest(request, ticketId, 'claim', {
        expected_version: expectedVersion
      })
    },
    resolve(ticketId, expectedVersion, resolution) {
      return actionRequest(request, ticketId, 'resolve', {
        expected_version: expectedVersion,
        resolution
      })
    },
    close(ticketId, expectedVersion) {
      return actionRequest(request, ticketId, 'close', {
        expected_version: expectedVersion
      })
    },
    reopen(ticketId, expectedVersion, reason) {
      return actionRequest(request, ticketId, 'reopen', {
        expected_version: expectedVersion,
        reason
      })
    },
    events(ticketId) {
      return request(`/api/v1/tickets/${encodeURIComponent(ticketId)}/events`)
    }
  }
}

export class TicketSubmission {
  constructor(createKey = defaultIdempotencyKey) {
    this.createKey = createKey
    this.inFlight = false
    this.pendingKey = ''
  }

  async submit(api, payload) {
    if (this.inFlight) return { duplicate: true, ticket: null }
    this.inFlight = true
    this.pendingKey ||= this.createKey()
    try {
      const ticket = await api.create(payload, this.pendingKey)
      this.pendingKey = ''
      return { duplicate: false, ticket }
    } finally {
      this.inFlight = false
    }
  }
}

export function buildActionPayload(action, ticket, text = '') {
  if (!ticket || !Number.isInteger(Number(ticket.version))) {
    throw new TypeError('ticket.version is required')
  }
  const payload = { expected_version: Number(ticket.version) }
  if (action === 'resolve') payload.resolution = String(text || '').trim()
  if (action === 'reopen') payload.reason = String(text || '').trim()
  return payload
}

export function canCreateTicket(accessToken, customerId) {
  return Boolean(String(accessToken || '').trim() && String(customerId || '').trim())
}

export function resolveSessionCustomerId(accessToken, fallback = '') {
  const claims = decodeJwtClaims(accessToken)
  const values = claims.customer_ids || claims.customerIds
  if (Array.isArray(values) && values.length) return String(values[0])
  if (claims.customer_id || claims.customerId) {
    return String(claims.customer_id || claims.customerId)
  }
  return String(fallback || '').trim()
}

export function resolveSessionRoles(accessToken, fallback = []) {
  const claims = decodeJwtClaims(accessToken)
  const values = claims.roles || claims.role || fallback
  const list = Array.isArray(values) ? values : [values]
  return [...new Set(list.map((value) => String(value || '').trim()).filter(Boolean))]
}

export function normalizeTicketError(error) {
  if (error instanceof TicketApiError) return error
  const data = error?.data || {}
  return new TicketApiError(
    data.message || error?.message || '工单请求失败',
    {
      status: Number(error?.status || 0),
      code: data.code || '',
      retryable: Boolean(data.retryable),
      requestId: data.requestId || ''
    }
  )
}

export function defaultIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `ticket-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
}

function actionRequest(request, ticketId, action, payload) {
  return request(`/api/v1/tickets/${encodeURIComponent(ticketId)}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

function decodeJwtClaims(token) {
  const parts = String(token || '').split('.')
  if (parts.length !== 3) return {}
  try {
    const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    if (typeof atob === 'function') {
      return JSON.parse(atob(padded))
    }
    return JSON.parse(Buffer.from(padded, 'base64').toString('utf8'))
  } catch {
    return {}
  }
}
