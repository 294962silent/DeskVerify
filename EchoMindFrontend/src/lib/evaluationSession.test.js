import assert from 'node:assert/strict'
import test from 'node:test'
import { createEvaluationSession } from './evaluationSession.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function completed(id) {
  return { job_id: id, status: 'succeeded', report: { id } }
}

test('refresh uses the current saved credential and removal clears the protected report', async () => {
  let requestCount = 0
  const evaluation = createEvaluationSession({
    readLatest: async () => {
      requestCount += 1
      if (requestCount === 1) throw new Error('403 ACCESS_DENIED')
      return completed('current')
    }
  })

  await evaluation.refresh(false)
  assert.equal(requestCount, 0)
  assert.match(evaluation.error.value, /访问令牌/)
  await evaluation.refresh(true)
  assert.match(evaluation.error.value, /403 ACCESS_DENIED/)
  await evaluation.refresh(true)
  assert.equal(evaluation.job.value.job_id, 'current')
  assert.equal(evaluation.data.value.id, 'current')
  assert.equal(evaluation.error.value, '')

  await evaluation.refresh(false)
  assert.equal(requestCount, 2)
  assert.equal(evaluation.job.value, null)
  assert.equal(evaluation.data.value, null)
  assert.match(evaluation.error.value, /访问令牌/)
})

test('late latest-job denial cannot replace a newer report', async () => {
  const old = deferred()
  let requestCount = 0
  const evaluation = createEvaluationSession({
    readLatest: () => ++requestCount === 1 ? old.promise : Promise.resolve(completed('new')),
  })

  const pending = evaluation.refresh(true)
  await evaluation.refresh(true)
  old.reject(new Error('denied for the previous session'))
  await pending
  assert.equal(evaluation.job.value.job_id, 'new')
  assert.equal(evaluation.error.value, '')
})

test('superseded submissions and progress responses cannot restore the old job', async () => {
  const submit = deferred()
  const progress = deferred()
  const notifications = []
  let polls = 0
  const evaluation = createEvaluationSession({
    readLatest: async () => null,
    readJob: () => { polls += 1; return progress.promise },
    notify: (message) => notifications.push(message),
  })

  await evaluation.refresh(true)
  let pending = evaluation.submit(() => submit.promise, 'submitted', 'submission failed')
  assert.equal(evaluation.submitting.value, true)
  await evaluation.refresh(false)
  submit.resolve({ job_id: 'old-submit', status: 'queued' })
  await pending
  assert.equal(evaluation.submitting.value, false)
  assert.equal(polls, 0)
  assert.deepEqual(notifications, [])

  await evaluation.refresh(true)
  pending = evaluation.submit(async () => ({ job_id: 'old-poll', status: 'queued' }), 'submitted', 'submission failed')
  await Promise.resolve()
  assert.equal(polls, 1)
  await evaluation.refresh(false)
  progress.resolve(completed('old-poll'))
  await pending
  assert.equal(evaluation.job.value, null)
  assert.equal(evaluation.data.value, null)
  assert.deepEqual(notifications, ['submitted'])
})

test('a pending Markdown download is discarded when authentication changes', async () => {
  const markdown = deferred()
  const evaluation = createEvaluationSession({
    readLatest: async () => completed('old'),
    readMarkdown: () => markdown.promise,
  })
  await evaluation.refresh(true)
  const pending = evaluation.download()
  await evaluation.refresh(false)
  markdown.resolve('Previous session protected report')
  assert.equal(await pending, null)
})
