import { ref } from 'vue'

export function createEvaluationSession({
  readLatest,
  readJob,
  readMarkdown,
  notify = () => {},
}) {
  const job = ref(null)
  const data = ref(null)
  const error = ref('')
  const submitting = ref(false)
  let hasToken = false
  let generation = 0
  let pollTimer

  function invalidate() {
    clearTimeout(pollTimer)
    return ++generation
  }

  function showJob(next) {
    job.value = next
    const failed = next.status === 'failed'
    const succeeded = next.status === 'succeeded'
    data.value = succeeded || (failed && next.report?.gate) ? next.report : null
    error.value = failed ? next.error || '评测任务失败，请查看服务日志。' : ''
    return succeeded || failed
  }

  async function poll(jobId, current) {
    if (current !== generation) return
    try {
      const next = await readJob(jobId)
      if (current !== generation) return
      if (showJob(next)) {
        notify(next.status === 'succeeded' ? '评测完成' : '评测运行失败')
      } else {
        pollTimer = setTimeout(() => poll(jobId, current), 1000)
      }
    } catch (cause) {
      if (current === generation) error.value = `读取评测进度失败：${cause.message}`
    }
  }

  async function refresh(authenticated) {
    const current = invalidate()
    hasToken = authenticated
    job.value = null
    data.value = null
    error.value = hasToken ? '' : '请先设置 Java 访问令牌。'
    submitting.value = false
    if (!hasToken) return

    try {
      const latest = await readLatest()
      if (current !== generation || !latest) return
      if (!showJob(latest)) await poll(latest.job_id, current)
    } catch (cause) {
      if (current === generation) error.value = `无法读取评测任务：${cause.message}`
    }
  }

  async function submit(createJob, submittedMessage, failedMessage) {
    if (!hasToken || submitting.value) return
    const current = invalidate()
    submitting.value = true
    error.value = ''
    try {
      const next = await createJob()
      if (current !== generation) return
      const done = showJob(next)
      notify(submittedMessage)
      if (!done) await poll(next.job_id, current)
    } catch (cause) {
      if (current !== generation) return
      error.value = cause.message
      notify(failedMessage)
    } finally {
      if (current === generation) submitting.value = false
    }
  }

  async function download() {
    if (!hasToken || !job.value?.job_id) return null
    const current = generation
    try {
      const markdown = await readMarkdown(job.value.job_id)
      return current === generation ? markdown : null
    } catch (cause) {
      if (current === generation) notify(`报告下载失败：${cause.message}`)
      return null
    }
  }

  return { job, data, error, submitting, refresh, submit, download, dispose: invalidate }
}
