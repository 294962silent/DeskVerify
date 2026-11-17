import test from 'node:test'
import assert from 'node:assert/strict'
import { javaAuthHeaders, saveAccessToken } from './auth.js'

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
  assert.deepEqual(javaAuthHeaders('python', session), {})
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
