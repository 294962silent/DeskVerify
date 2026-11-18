import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildGoldenRagEvaluationRequest,
  metricRows,
  normalizeGoldenRagReport,
} from './goldenEvaluation.js'

test('golden evaluation request uses snake_case dataset version', () => {
  assert.deepEqual(buildGoldenRagEvaluationRequest(), {
    dataset_version: 'rag-support-golden-v1',
  })
})

test('golden report normalization preserves provenance metrics and failed cases', () => {
  const report = normalizeGoldenRagReport({
    dataset_version: 'rag-support-golden-v1',
    dataset_sha256: 'abc',
    code_sha: 'def',
    embedding_model: 'bge-small-zh-v1.5-onnx-fp32',
    provenance: {
      kind: 'synthetic',
      human_reviewed: false,
      statistically_representative: false,
      external_llm_tokens: 0,
    },
    totals: { retrieval_cases: 30, handoff_cases: 20 },
    retrieval_metrics: {
      ALL: { recall_at5: 1, citation_hit_rate: 1, acl_violations: 0 },
    },
    handoff_metrics: {
      ALL: { accuracy: 1, precision: 1, recall: 1 },
    },
    retrieval_cases: [{ case_id: 'rag-1', passed: false }],
    handoff_cases: [{ case_id: 'handoff-1', passed: true }],
    limitations: ['Synthetic dataset'],
    report_download_path: '/eval/jobs/job-1/report.md',
  })

  assert.equal(report.datasetVersion, 'rag-support-golden-v1')
  assert.equal(report.datasetSha256, 'abc')
  assert.equal(report.provenance.humanReviewed, false)
  assert.equal(report.provenance.externalLlmTokens, 0)
  assert.equal(report.retrievalMetrics[0].recall_at5, 1)
  assert.equal(report.handoffMetrics[0].accuracy, 1)
  assert.equal(report.failedRetrievalCases.length, 1)
  assert.equal(report.failedHandoffCases.length, 0)
})

test('metric rows use a stable split order', () => {
  const rows = metricRows({
    ALL: { accuracy: 1 },
    DEV: { accuracy: 0.9 },
    HOLDOUT: { accuracy: 0.8 },
  })
  assert.deepEqual(rows.map((row) => row.split), ['DEV', 'HOLDOUT', 'ALL'])
})
