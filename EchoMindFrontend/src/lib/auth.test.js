import test from 'node:test'
import assert from 'node:assert/strict'
import { authorizedFetch, javaAuthHeaders, saveAccessToken } from './auth.js'

function storage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  }
}

test('Java requests receive the session Bearer token', () => {
  const session = storage({ echomind_access_token: 'test-token' })
  assert.deepEqual(javaAuthHeaders('java', session), {
    Authorization: 'Bearer test-token'
  })
})

test('missing Java token fails before a request can be made', () => {
  assert.throws(
    () => javaAuthHeaders('java', storage()),
    /Java backend requires an access token/
  )
})

test('operator token is stored only in session storage', () => {
  const session = storage()
  saveAccessToken('operator-token', session)
  assert.equal(session.getItem('echomind_access_token'), 'operator-token')
})

test('Java transport sends the bearer header to mocked fetch', async () => {
  const calls = []
  const fetchImpl = async (...args) => {
    calls.push(args)
    return new Response('{}', { status: 200 })
  }
  const session = storage({ echomind_access_token: 'test-token' })

  await authorizedFetch('/api/java/health', 'java', {}, session, fetchImpl)

  assert.equal(calls.length, 1)
  assert.equal(calls[0][1].headers.Authorization, 'Bearer test-token')
})

test('missing Java token rejects before mocked fetch is called', async () => {
  let fetchCalls = 0
  const fetchImpl = async () => {
    fetchCalls++
    return new Response('{}', { status: 200 })
  }

  await assert.rejects(
    authorizedFetch('/api/java/health', 'java', {}, storage(), fetchImpl),
    /Java backend requires an access token/
  )
  assert.equal(fetchCalls, 0)
})

test('all DeskVerify transports require the session token', async () => {
  let fetchCalls = 0
  const fetchImpl = async (...args) => {
    fetchCalls++
    return new Response('{}', { status: 200 })
  }

  await assert.rejects(
    authorizedFetch('/api/java/health', 'legacy', {}, storage(), fetchImpl),
    /access token/
  )
  assert.equal(fetchCalls, 0)
})
