export const DEFAULT_GOLDEN_DATASET = 'rag-support-golden-v1'

export function buildGoldenRagEvaluationRequest(
  datasetVersion = DEFAULT_GOLDEN_DATASET,
) {
  return { dataset_version: datasetVersion }
}

export function normalizeGoldenRagReport(raw = null) {
  const report = raw || {}
  const datasetVersion = report.dataset_version || report.datasetVersion
  if (!datasetVersion) return null
  return {
    datasetVersion,
    datasetSha256: report.dataset_sha256 || report.datasetSha256 || '',
    codeSha: report.code_sha || report.codeSha || '',
    embeddingModel: report.embedding_model || report.embeddingModel || '',
    provenance: {
      kind: report.provenance?.kind || 'synthetic',
      humanReviewed: Boolean(
        report.provenance?.human_reviewed ?? report.provenance?.humanReviewed,
      ),
      statisticallyRepresentative: Boolean(
        report.provenance?.statistically_representative
          ?? report.provenance?.statisticallyRepresentative,
      ),
      externalLlmTokens: Number(
        report.provenance?.external_llm_tokens
          ?? report.provenance?.externalLlmTokens
          ?? 0,
      ),
    },
    totals: report.totals || {},
    retrievalMetrics: metricRows(report.retrieval_metrics || report.retrievalMetrics),
    handoffMetrics: metricRows(report.handoff_metrics || report.handoffMetrics),
    failedRetrievalCases: (report.retrieval_cases || report.retrievalCases || [])
      .filter((item) => !item.passed),
    failedHandoffCases: (report.handoff_cases || report.handoffCases || [])
      .filter((item) => !item.passed),
    limitations: report.limitations || [],
    downloadPath: report.report_download_path || report.reportDownloadPath || '',
  }
}

export function metricRows(metrics = {}) {
  return ['DEV', 'HOLDOUT', 'ALL']
    .filter((split) => metrics?.[split])
    .map((split) => ({ split, ...metrics[split] }))
}
