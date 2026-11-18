import assert from 'node:assert/strict'
import test from 'node:test'
import {
  TicketApiError,
  TicketSubmission,
  buildActionPayload,
  canCreateTicket,
  createTicketClient,
  normalizeTicketError,
  resolveSessionCustomerId,
  resolveSessionRoles
} from '../lib/tickets.js'

test('customer creation requires both a session token and customer context', () => {
  assert.equal(canCreateTicket('', 'customer-001'), false)
  assert.equal(canCreateTicket('token', ''), false)
  assert.equal(canCreateTicket('token', 'customer-001'), true)
})

test('concurrent create attempts share one idempotency key and submit once', async () => {
  let calls = 0
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const api = {
    async create(_payload, key) {
      calls += 1
      await gate
      return { ticket_id: 'ticket-1', key }
    }
  }
  const submission = new TicketSubmission(() => 'fixed-key')

  const first = submission.submit(api, { subject: 'one' })
  const duplicate = await submission.submit(api, { subject: 'one' })
  release()
  const completed = await first

  assert.equal(calls, 1)
  assert.equal(duplicate.duplicate, true)
  assert.equal(completed.ticket.ticket_id, 'ticket-1')
  assert.equal(completed.ticket.key, 'fixed-key')
})

test('failed create retries with the same key and clears it after success', async () => {
  const keys = []
  let keySequence = 0
  let shouldFail = true
  const api = {
    async create(_payload, key) {
      keys.push(key)
      if (shouldFail) {
        shouldFail = false
        throw new Error('network down')
      }
      return { ticket_id: 'ticket-1' }
    }
  }
  const submission = new TicketSubmission(
    () => ['stable-key', 'next-key'][keySequence++] || 'fallback-key'
  )

  await assert.rejects(() => submission.submit(api, { subject: 'one' }), /network down/)
  await submission.submit(api, { subject: 'one' })
  await submission.submit(api, { subject: 'two' })

  assert.deepEqual(keys, ['stable-key', 'stable-key', 'next-key'])
})

test('action payload always carries the current optimistic version', () => {
  const ticket = { version: 4 }
  assert.deepEqual(buildActionPayload('claim', ticket), { expected_version: 4 })
  assert.deepEqual(buildActionPayload('resolve', ticket, '  fixed  '), {
    expected_version: 4,
    resolution: 'fixed'
  })
  assert.deepEqual(buildActionPayload('reopen', ticket, ' returned '), {
    expected_version: 4,
    reason: 'returned'
  })
})

test('ticket client encodes IDs and sends the create idempotency header', async () => {
  const calls = []
  const client = createTicketClient(async (path, options = {}) => {
    calls.push({ path, options })
    return { ok: true }
  })

  await client.create({ subject: 'one' }, 'idem-1')
  await client.get('ticket/a')
  await client.claim('ticket/a', 2)

  assert.equal(calls[0].path, '/api/v1/tickets')
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'idem-1')
  assert.equal(calls[1].path, '/api/v1/tickets/ticket%2Fa')
  assert.deepEqual(JSON.parse(calls[2].options.body), { expected_version: 2 })
})

test('ticket errors preserve state conflict and foreign lookup semantics', () => {
  const conflict = normalizeTicketError({
    status: 409,
    data: {
      code: 'TICKET_STATE_CONFLICT',
      message: 'ticket state conflict',
      retryable: false
    }
  })
  assert.ok(conflict instanceof TicketApiError)
  assert.equal(conflict.status, 409)
  assert.equal(conflict.code, 'TICKET_STATE_CONFLICT')
  assert.equal(conflict.retryable, false)

  const missing = normalizeTicketError({
    status: 404,
    data: { code: 'RESOURCE_NOT_FOUND', message: 'resource not found' }
  })
  assert.equal(missing.status, 404)
  assert.equal(missing.message, 'resource not found')
})

test('session customer and role hints come only from token claims or host fallback', () => {
  const payload = Buffer.from(JSON.stringify({
    customer_ids: ['customer-007'],
    roles: ['SUPPORT_AGENT', 'TENANT_ADMIN']
  })).toString('base64url')
  const token = `header.${payload}.signature`

  assert.equal(resolveSessionCustomerId(token, 'customer-001'), 'customer-007')
  assert.deepEqual(resolveSessionRoles(token, ['CUSTOMER']), [
    'SUPPORT_AGENT',
    'TENANT_ADMIN'
  ])
  assert.equal(resolveSessionCustomerId('invalid', 'customer-001'), 'customer-001')
})
