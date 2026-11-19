import assert from 'node:assert/strict'
import test from 'node:test'
import { requestChat } from './backends.js'

test('chat sends scoped customer and reuses the Java conv_id for the next turn', async (t) => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body) })
    return new Response(JSON.stringify({
      conv_id: 'fixture-conversation',
      handoff_ticket_id: 'fixture-ticket',
      response: 'Created',
    }))
  })
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: { getItem: () => 'fixture-session' }
  })
  t.after(() => { delete globalThis.sessionStorage })

  const settings = { endpoints: { java: '/api/java' }, conversationId: '' }
  const answer = await requestChat('java', settings, '转人工', 'customer-fixture')
  settings.conversationId = answer.conversationId
  await requestChat('java', settings, '转人工', 'customer-fixture')

  assert.equal(answer.handoffTicketId, 'fixture-ticket')
  assert.deepEqual(requests, [
    { url: '/api/java/chat', body: { message: '转人工', customer_id: 'customer-fixture' } },
    {
      url: '/api/java/chat',
      body: { message: '转人工', conv_id: 'fixture-conversation', customer_id: 'customer-fixture' },
    },
  ])
})
