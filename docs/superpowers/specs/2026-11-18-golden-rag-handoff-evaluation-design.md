# Golden RAG And Handoff Evaluation Design

Date: 2026-11-18

Status: Approved for specification review

## Goal

建立一个小规模、可重复、版本化的 Golden Dataset，用本地真实 BGE
embedding 和 MySQL 检索链路验证 RAG 与转人工策略的方法有效性，并把结果接入现有异步评测报告。

## Scope Boundary

This dataset is a synthetic method-validation baseline:

- All cases are authored synthetic scenarios.
- No human annotator has reviewed the cases.
- The dataset is not statistically representative of production traffic.
- Results prove reproducibility and regression behavior, not real-world answer quality.
- The first version uses no external LLM calls and consumes zero DeepSeek API tokens.

The report must state:

```text
provenance=synthetic
human_reviewed=false
statistically_representative=false
external_llm_tokens=0
```

## Dataset Version

The first immutable dataset version is:

```text
rag-support-golden-v1
```

Dataset resources live under:

```text
EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/
  manifest.json
  dataset.json
```

The directory is immutable after release. Any document, expected reference,
case, split, or checksum change requires a new dataset version directory.

## Dataset Size And Split

The first version contains:

- 12 to 15 fixed knowledge documents.
- 20 to 30 fixed chunks.
- 30 retrieval cases.
- 20 handoff-policy cases.

Retrieval split:

```text
DEV     20 cases
HOLDOUT 10 cases
```

Handoff split:

```text
DEV     12 cases
HOLDOUT  8 cases
```

Only DEV cases may be used when tuning thresholds or reranking weights. HOLDOUT
cases are used for the final report and must not be used for iterative tuning.

## Synthetic Evaluation Tenant

Dataset documents are loaded into a dedicated tenant:

```text
tenant-golden-v1
```

The HTTP caller cannot select another evaluation tenant. The caller's identity
only authorizes and owns the asynchronous job; retrieval always executes with
the fixed dataset actors:

```text
GOLDEN_TENANT_ADMIN
GOLDEN_CUSTOMER_A
GOLDEN_CUSTOMER_B
```

This prevents evaluation data from being written into a real customer tenant.

## Dataset Contract

`dataset.json` contains `dataset_version`, `schema_version`, `provenance`,
`documents`, `retrieval_cases`, and `handoff_cases`.

Each document fixes:

```text
document_id
title
version
visibility
customer_id
normalized_content_sha256
content
expected_chunks[]
```

Each expected chunk fixes:

```text
chunk_index
chunk_id
checksum
section_title
heading_path
```

Each retrieval case fixes:

```text
case_id
split
query
actor_scope
expected_document_id
expected_version_number
expected_chunk_id
expected_heading_path
expect_citation
```

Each handoff case fixes:

```text
case_id
split
message
signals
expected_handoff
expected_reason
```

Handoff signals are explicit booleans such as:

```text
explicit_human_request
critical_urgency
security_or_privacy
monetary_dispute
irreversible_action
verified_knowledge_available
```

The handoff evaluator tests a deterministic `HandoffPolicy`. It does not call an
LLM and must be reported as policy-level handoff quality.

## Manifest Contract

`manifest.json` fixes:

```text
dataset_version
schema_version
created_on
embedding_model
dataset_file_sha256
document_count
chunk_count
retrieval_case_count
handoff_case_count
retrieval_split_counts
handoff_split_counts
provenance
```

The loader recomputes the digest and counts. A mismatch fails the job before any
evaluation data is used.

## Evaluation Architecture

New application components:

- `GoldenDatasetLoader`: loads resources, verifies the manifest, and validates
  unique IDs, counts, references, versions, and chunks.
- `GoldenKnowledgeImporter`: idempotently imports fixed documents and versions
  into `tenant-golden-v1`.
- `GoldenRagEvaluator`: executes retrieval cases and calculates retrieval,
  citation, ACL, degradation, and latency metrics.
- `HandoffPolicy`: deterministic handoff decision shared by evaluation and the
  Agent runtime.
- `HandoffEvaluator`: executes handoff cases and calculates confusion metrics.
- `GoldenEvaluationReportWriter`: produces JSON and Markdown reports.

The existing asynchronous `EvaluationJobService` owns job lifecycle and tenant
ownership. The new request selects `rag-support-golden-v1`; callers cannot
submit arbitrary case data through this endpoint.

## Data Flow

```text
tenant admin
  -> POST /eval/golden-rag
  -> create tenant-owned async job
  -> load and verify dataset manifest
  -> require live BGE provider
  -> idempotently seed tenant-golden-v1
  -> execute 30 retrieval cases
  -> execute 20 deterministic handoff cases
  -> calculate DEV / HOLDOUT / aggregate metrics
  -> persist JSON report in the evaluation job
  -> generate Markdown report
```

A successful rerun must reuse the same fixed document versions and chunks. It
must not create additional documents, versions, or chunks.

## Retrieval Metrics

Retrieval uses top K=5 and reports:

- Recall@1
- Recall@3
- Recall@5
- MRR@5
- Expected-document hit rate
- Expected-version hit rate
- Expected-chunk hit rate
- Citation hit rate
- ACL violation count
- Degraded-case rate
- P50 latency
- P95 latency

Every expected chunk must resolve to exactly one active PUBLISHED chunk under
the expected dataset tenant and actor scope.

## Handoff Metrics

The handoff report contains:

- Accuracy
- Precision
- Recall
- False-escalation rate
- Missed-handoff rate
- Confusion matrix
- Per-reason breakdown

The report labels these metrics `policy_level=true`.

## Report Contract

The job JSON includes:

```text
job_id
dataset_version
dataset_sha256
code_sha
embedding_model
provenance
generated_at
totals
retrieval_metrics
handoff_metrics
limitations
cases
```

Metrics are reported separately for DEV, HOLDOUT, and ALL.

The Markdown report includes the same metadata, metric tables, failed-case
details, and explicit limitations. It must not describe synthetic results as
production evidence.

The existing frontend evaluation page displays:

- Dataset version and digest.
- Synthetic/human-review flags.
- DEV and HOLDOUT metrics.
- Failed retrieval and handoff cases.
- Markdown report download link.

## Error Handling

- Unknown dataset version: reject the request without creating an evaluation
  dataset write.
- Manifest/count/reference mismatch: job fails with
  `GOLDEN_DATASET_INTEGRITY_FAILED`.
- BGE model unavailable: job fails with `EMBEDDING_UNAVAILABLE`; no degraded
  quality result is recorded.
- Existing document/version mismatch: job fails without overwriting data.
- Duplicate case or chunk ID: dataset integrity failure.
- Missing expected active chunk: case fails and is retained in the report.
- Report persistence failure: job fails instead of returning a partial success.

## Testing

Required tests:

1. Dataset integrity test:
   - Exactly 30 retrieval and 20 handoff cases.
   - Splits are 20/10 and 12/8.
   - All IDs are unique.
   - Every expected document, version, and chunk exists.
   - Expected chunk IDs and checksums match the deterministic chunker.
2. Idempotent import test:
   - First run creates fixed rows in `tenant-golden-v1`.
   - Second run creates no additional rows.
3. MySQL + real BGE integration test:
   - Executes all retrieval cases.
   - Produces Recall@K, citation, ACL, and latency metrics.
4. Handoff policy test:
   - Confusion metrics and reason breakdown match fixed expectations.
5. API/job test:
   - Tenant admin can submit and retrieve the job.
   - Non-admin access is denied.
   - Reports remain tenant/owner scoped.
6. Report test:
   - JSON and Markdown include dataset digest, code SHA, provenance, splits, and
     limitations.
   - External LLM token usage is zero.

## Acceptance Criteria

- The dataset contains exactly 30 retrieval and 20 handoff cases.
- All documents, versions, chunk IDs, checksums, citations, and expected
  handoff labels are fixed in the versioned resource.
- Repeated evaluation is idempotent.
- BGE and MySQL execute the real retrieval path.
- ACL violations are zero for the v1 dataset.
- A complete JSON and Markdown report is available through the async evaluation
  workflow.
- The report clearly states that the dataset is synthetic and not
  production-representative.
- V1 consumes zero DeepSeek API tokens.

## Non-Goals

- Human-reviewed or production-traffic evaluation.
- Statistical significance claims.
- Large-scale corpus benchmarking.
- LLM-as-Judge answer-quality scoring.
- External DS API calls in v1.
- ANN or production vector-index performance testing.
- Replacing integration, security, or load testing.
