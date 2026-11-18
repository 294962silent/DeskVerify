# Golden RAG And Handoff V1

Date: 2026-11-19

Dataset: `rag-support-golden-v1`

## Goal

Build a small, immutable, synthetic evaluation baseline for the real
MySQL/BGE retrieval path and the deterministic handoff policy.

This dataset verifies method behavior and regression stability. It is not a
human-reviewed or production-representative benchmark.

## Provenance

```text
kind=synthetic
human_reviewed=false
statistically_representative=false
external_llm_tokens=0
dataset_sha256=d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968
```

The dataset files are:

```text
EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/dataset.json
EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/manifest.json
EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/baseline.json
```

## Dataset Shape

- 12 fixed knowledge documents.
- 12 fixed chunks, one per document.
- 30 retrieval cases: 20 DEV and 10 HOLDOUT.
- 20 handoff-policy cases: 12 DEV and 8 HOLDOUT.
- Fixed document IDs, version `1`, chunk IDs, chunk checksums, heading paths,
  expected citations, and expected handoff reasons.
- Dedicated tenant: `tenant-golden-v1`.
- Customer ACL examples for `customer-a` and `customer-b`.

The dataset loader recomputes the file digest, validates all references against
the deterministic chunker, and rejects duplicate or inconsistent IDs.

## Runtime Flow

```text
POST /eval/golden-rag/jobs
  -> asynchronous, tenant-owned job
  -> verify dataset manifest
  -> require live BGE ONNX
  -> idempotently import fixed documents into tenant-golden-v1
  -> execute 30 retrieval cases through MySQL + BGE + RRF + rerank
  -> execute 20 deterministic handoff cases
  -> evaluate blocking and advisory release-gate checks
  -> write JSON and Markdown reports
```

Request:

```json
{
  "dataset_version": "rag-support-golden-v1"
}
```

Markdown report:

```text
GET /eval/jobs/{jobId}/report.md
```

## Baseline Observed On This Machine

The real MySQL 8.4 + BGE run completed with:

| Split | Recall@1 | Recall@3 | Recall@5 | MRR@5 | Citation hit | ACL violations | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DEV | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 0 | 44 ms |
| HOLDOUT | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 0 | 37 ms |
| ALL | 1.00 | 1.00 | 1.00 | 1.00 | 1.00 | 0 | 44 ms |

Handoff policy:

| Split | Accuracy | Precision | Recall | False escalation | Missed handoff |
| --- | ---: | ---: | ---: | ---: | ---: |
| DEV | 1.00 | 1.00 | 1.00 | 0.00 | 0.00 |
| HOLDOUT | 1.00 | 1.00 | 1.00 | 0.00 | 0.00 |
| ALL | 1.00 | 1.00 | 1.00 | 0.00 | 0.00 |

The perfect result is expected for this small, purpose-built corpus. It proves
the fixed test path and ACL behavior are working; it does not prove broad
real-world RAG quality.

## Release Gate And CI

The versioned baseline file is the source of accepted DEV/HOLDOUT quality.
The default blocking checks are:

- dataset version and SHA-256 match the fixed baseline;
- DEV/HOLDOUT Recall@5, MRR@5, and citation hit rate may drop by at most
  `0.02`;
- ACL violations must remain `0`;
- degraded retrieval rate must remain `0`;
- handoff Recall must be at least `0.95`;
- false escalation and missed handoff rates must be at most `0.10`.

ALL P95 latency above `2000 ms` is reported as an advisory warning but does
not block the small V1 benchmark. The generated report contains 18 checks.
When blocking checks fail, the async job becomes `failed` / `gate_failed` with
`GOLDEN_EVALUATION_GATE_FAILED`, while its JSON and Markdown evidence remains
available.

GitHub Actions downloads and SHA-256-verifies the BGE model, pulls MySQL 8.4,
runs the complete backend suite, and executes
`scripts/assert-golden-evaluation.ps1`. That assertion fails if the Golden E2E
was skipped or if any blocking gate check fails. Surefire, Golden reports, and
the frontend production build are uploaded as workflow artifacts.

## Idempotency

Running the evaluator again reuses the same fixed document and chunk rows.
The importer verifies:

- version and source checksum;
- embedding model;
- chunk count, chunk IDs, and chunk checksums;
- section and heading metadata;
- tenant/customer ACL;
- `PUBLISHED` status and document current version.

## Bugs And Fixes

### 1. Final Transactional Bean Could Not Be Proxied

Observed:

```text
Cannot subclass final class GoldenKnowledgeImporter
```

Cause:

- `GoldenKnowledgeImporter` used `@Transactional` while being declared `final`.
- Spring CGLIB attempted to create a transactional proxy.

Fix:

- Removed `final` from the transactional service class.

### 2. Tenant-Wide Actors Were Counted As ACL Violations

Observed:

- A tenant admin retrieving a customer-specific chunk was incorrectly counted as
  an ACL violation in the evaluator.

Cause:

- ACL checks considered only customer membership and ignored the tenant-wide
  support/admin scope.

Fix:

- `TENANT_ADMIN` and `SUPPORT_AGENT` actors are now excluded from customer ACL
  violation counts.

### 3. Unit Tests Used Camel-Case Jackson

Observed:

- Evaluation reports in tests used `datasetVersion`, while production uses
  `dataset_version`.

Cause:

- The unit test constructed a default `ObjectMapper` instead of the configured
  SNAKE_CASE mapper.

Fix:

- Test Mapper now uses `PropertyNamingStrategies.SNAKE_CASE`.

### 4. Web MVC Slice Missing Golden Evaluation Dependency

Observed:

- `Task6bSecurityBoundaryTest` imported the real `EvaluationJobService` but did
  not provide the new `GoldenEvaluationService` dependency.

Fix:

- Added a `@MockitoBean` for `GoldenEvaluationService` in that test slice.

### 5. Gate Test Initially Used A Placeholder Dataset Digest

Observed:

- Loading the real classpath baseline made the passing gate fixture fail.

Cause:

- The first unit test mocked the baseline with a placeholder digest. Replacing
  the mock with the real JSON loader exposed that the test data did not use the
  fixed dataset SHA-256.

Fix:

- The test now loads the real versioned baseline and uses
  `d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968`.

### 6. Frontend Patch Changed Entire-File Line Endings

Observed:

- The first frontend commit showed thousands of changed lines despite only
  171 semantic additions.

Cause:

- `App.vue` and `styles.css` were normalized from LF to CRLF during the patch
  workflow.

Fix:

- Restored LF for those files, verified the semantic diff was `34` and `67`
  added lines respectively, and amended the local unpushed commit.

### 7. CI Deleted Temp Directory While An Evaluation Worker Was Still Finishing

Observed:

- GitHub Actions failed `EvaluationJobServiceTest` with
  `Failed to delete temp directory`.
- The same suite usually passed locally, making this timing-sensitive.

Cause:

- `EvaluationJobService.close()` called `shutdownNow()` and immediately
  returned.
- A worker interrupted by shutdown could still save its final state while
  JUnit removed the `@TempDir`.

Fix:

- Shutdown now waits up to five seconds outside the service monitor for the
  executor to terminate, then obtains the monitor and persists final job state.
- A regression test uses a worker that deliberately ignores the first
  interrupt and verifies that `close()` does not return before worker cleanup.

## Verification

Latest local verification on this machine:

- Backend GitHub Actions full suite: 57 Surefire suites, 272 tests,
  0 failures, 0 errors,
  0 skips.
- Golden E2E: passed against MySQL 8.4 + BGE ONNX with the fixed dataset
  digest, fixed document/chunk IDs, JSON and Markdown reports, and repeated
  import idempotency.
- Golden artifact gate: `gate.passed=true`, 18 checks, E2E tests=1,
  skipped=0.
- Report authorization: tenant and user ownership checks passed.
- Frontend GitHub Actions job: 21 tests passed and Vite production build
  passed.
- CI run:
  `https://github.com/294962silent/EchoMind-SaaS-Agent/actions/runs/44346435272`.
- CI artifacts: `backend-surefire-reports`, `golden-evaluation-reports`, and
  `frontend-dist` were uploaded successfully.

The all-perfect DEV/HOLDOUT metrics above are the observed baseline for this
small synthetic dataset, not evidence of production generalization.

## Commands

```powershell
$env:JAVA_HOME='C:\Users\MLL\AppData\Local\Temp\echomind-jdk21\jdk-21.0.13+9'
$env:Path="$env:JAVA_HOME\bin;D:\Docker\Docker\resources\bin;$env:Path"
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'

cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\mvnw.cmd '-Dtest=GoldenEvaluationEndToEndTest' test
.\mvnw.cmd test
pwsh -NoProfile -File .\scripts\assert-golden-evaluation.ps1 `
  -ReportDirectory .\target\golden-e2e\reports `
  -SurefireDirectory .\target\surefire-reports

cd ..\EchoMindFrontend
npm test -- --run
npm run build
```

## Remaining Boundaries

- Synthetic data only; no human labels or production traffic.
- V1 proves method wiring, not statistical significance.
- Retrieval cases use one fixed expected chunk each.
- Handoff metrics are policy-level and do not call an external LLM.
- P95 is advisory in V1 because shared CI and network jitter are not controlled.
- The dataset should be versioned as `rag-support-golden-v2` when content,
  references, splits, or checksums change.
