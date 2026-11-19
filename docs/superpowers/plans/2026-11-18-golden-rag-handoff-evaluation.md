# Golden RAG And Handoff Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Subagents are prohibited for this project by explicit user instruction.

**Goal:** Build an immutable small synthetic Golden Dataset and execute it through the real MySQL/BGE RAG path and deterministic handoff policy, with versioned JSON and Markdown reports.

**Architecture:** Dataset resources and digest verification are isolated from fixed-ID knowledge import. Retrieval and handoff evaluation produce independent case metrics, while a report writer assembles one tenant-owned asynchronous evaluation report. V1 uses no external LLM calls.

**Tech Stack:** Java 21, Spring Boot, Jackson, MySQL 8.4, Testcontainers, BGE ONNX, Vue 3, Node test runner, GitHub-compatible resources.

## Global Constraints

- Dataset version: `rag-support-golden-v1`.
- Exactly 30 retrieval cases: 20 DEV and 10 HOLDOUT.
- Exactly 20 handoff-policy cases: 12 DEV and 8 HOLDOUT.
- Evaluation tenant: `tenant-golden-v1`.
- Provenance: synthetic, not human-reviewed, not statistically representative.
- External LLM tokens: exactly 0.
- Real BGE ONNX embedding and MySQL retrieval are mandatory; degraded retrieval is not accepted as a quality result.
- Dataset resources are immutable after release; content changes require `rag-support-golden-v2`.
- V1 contains 12 fixed documents with one deterministic chunk each.
- No subagents may be used.

---

### Task 1: Versioned Dataset And Manifest Integrity

**Files:**
- Create: `EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/dataset.json`
- Create: `EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/manifest.json`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenDataset.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenManifest.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenDatasetException.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenDatasetLoader.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenDatasetLoaderTest.java`

**Interfaces:**
- Produces: `GoldenDataset GoldenDatasetLoader.load(String datasetVersion)`
- Produces: immutable dataset records including `documents`, `retrievalCases`, and `handoffCases`.
- Produces error code: `GOLDEN_DATASET_INTEGRITY_FAILED`.

**Dataset document IDs:**

```text
golden-doc-plan-entitlements
golden-doc-refund-policy
golden-doc-subscription-renewal
golden-doc-invoice-request
golden-doc-payment-failure
golden-doc-sso-login
golden-doc-api-rate-limit
golden-doc-account-security
golden-doc-ticket-sla
golden-doc-data-export
golden-doc-customer-a-discount
golden-doc-customer-b-discount
```

Each document uses version `1`, chunk index `0`, and a fixed chunk ID derived exactly as:

```text
"chunk-" + sha256(document_id + ":1:0").substring(0, 32)
```

- [ ] **Step 1: Write integrity tests**

```java
@Test
void v1HasRequiredCountsSplitsAndReferences() {
    GoldenDataset dataset = loader.load("rag-support-golden-v1");
    assertThat(dataset.documents()).hasSize(12);
    assertThat(dataset.retrievalCases()).hasSize(30);
    assertThat(dataset.handoffCases()).hasSize(20);
    assertThat(dataset.retrievalCases().stream()
            .filter(c -> c.split() == Split.DEV)).hasSize(20);
    assertThat(dataset.retrievalCases().stream()
            .filter(c -> c.split() == Split.HOLDOUT)).hasSize(10);
    assertThat(dataset.handoffCases().stream()
            .filter(c -> c.split() == Split.DEV)).hasSize(12);
    assertThat(dataset.handoffCases().stream()
            .filter(c -> c.split() == Split.HOLDOUT)).hasSize(8);
}
```

Add tests for duplicate IDs, missing expected chunks, changed manifest digest, and unsupported dataset version.

- [ ] **Step 2: Run the failing tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenDatasetLoaderTest' test
```

Expected: compilation failure because the Golden Dataset classes do not exist.

- [ ] **Step 3: Implement dataset records and loader**

Use Java records:

```java
public record GoldenDataset(
        String datasetVersion,
        int schemaVersion,
        Provenance provenance,
        List<Document> documents,
        List<RetrievalCase> retrievalCases,
        List<HandoffCase> handoffCases) {
    public enum Split { DEV, HOLDOUT }
    public enum ActorScope { TENANT_ADMIN, CUSTOMER_A, CUSTOMER_B }
    public record Provenance(String kind, boolean humanReviewed,
            boolean statisticallyRepresentative, int externalLlmTokens) {}
    public record Document(String documentId, String title, int version,
            String visibility, String customerId, String contentSha256,
            String content, List<Chunk> chunks) {}
    public record Chunk(int chunkIndex, String chunkId, String checksum,
            String sectionTitle, String headingPath) {}
    public record RetrievalCase(String caseId, Split split, String query,
            ActorScope actorScope, String expectedDocumentId,
            int expectedVersionNumber, String expectedChunkId,
            String expectedHeadingPath, boolean expectCitation) {}
    public record HandoffCase(String caseId, Split split, String message,
            HandoffSignals signals, boolean expectedHandoff,
            String expectedReason) {}
    public record HandoffSignals(boolean explicitHumanRequest,
            boolean criticalUrgency, boolean securityOrPrivacy,
            boolean monetaryDispute, boolean irreversibleAction,
            boolean verifiedKnowledgeAvailable) {}
}
```

The loader must:

1. Read both classpath resources for the requested version.
2. Canonicalize and SHA-256 hash `dataset.json`.
3. Compare with `manifest.dataset_file_sha256`.
4. Compare exact document, chunk, retrieval, handoff, and split counts.
5. Reject duplicate IDs.
6. Validate every retrieval reference points to one fixed document/version/chunk.
7. Validate chunk checksum and chunk ID against the deterministic chunker.

- [ ] **Step 4: Add the complete v1 JSON**

Create 12 short Markdown documents, one chunk each. Cover plan entitlements,
refunds, renewal, invoices, payment failures, SSO, API limits, account security,
ticket SLA, data export, customer A discount, and customer B discount.

Create 30 retrieval cases across direct questions, paraphrases, similar-document
distractors, and tenant/customer ACL scope. Case IDs are:

```text
rag-dev-001 through rag-dev-020
rag-holdout-001 through rag-holdout-010
```

Create 20 handoff cases. Case IDs are:

```text
handoff-dev-001 through handoff-dev-012
handoff-holdout-001 through handoff-holdout-008
```

Half of each split must expect handoff and half must not. Reasons use:

```text
EXPLICIT_HUMAN_REQUEST
CRITICAL_URGENCY
SECURITY_OR_PRIVACY
MONETARY_DISPUTE
IRREVERSIBLE_ACTION
KNOWLEDGE_SUFFICIENT
SELF_SERVICE_AVAILABLE
```

Write the exact generated manifest digest and counts.

- [ ] **Step 5: Run the integrity tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenDatasetLoaderTest' test
```

Expected: all dataset integrity tests pass.

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/resources/eval EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden
git commit -m "feat: add versioned golden rag dataset"
```

---

### Task 2: Deterministic Handoff Policy

**Files:**
- Create: `EchoMindJava/src/main/java/com/echomind/agent/HandoffSignals.java`
- Create: `EchoMindJava/src/main/java/com/echomind/agent/HandoffDecision.java`
- Create: `EchoMindJava/src/main/java/com/echomind/agent/HandoffPolicy.java`
- Create: `EchoMindJava/src/main/java/com/echomind/agent/HandoffSignalExtractor.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/agent/AgentOrchestrator.java`
- Test: `EchoMindJava/src/test/java/com/echomind/agent/HandoffPolicyTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/agent/AgentOrchestratorHandoffTest.java`

**Interfaces:**
- Produces: `HandoffDecision HandoffPolicy.decide(HandoffSignals signals)`
- Produces: `HandoffSignals HandoffSignalExtractor.from(AgentRequest request, AgentResponse response)`
- `HandoffDecision` contains `boolean handoff`, `String reason`, and `List<String> triggeredRules`.

Priority order is fixed:

```text
EXPLICIT_HUMAN_REQUEST
CRITICAL_URGENCY
SECURITY_OR_PRIVACY
IRREVERSIBLE_ACTION
MONETARY_DISPUTE
KNOWLEDGE_SUFFICIENT
SELF_SERVICE_AVAILABLE
```

- [ ] **Step 1: Write policy tests**

```java
@Test
void explicitHumanRequestAlwaysEscalates() {
    HandoffDecision decision = policy.decide(
            new HandoffSignals(true, false, false, false, false, true));
    assertThat(decision.handoff()).isTrue();
    assertThat(decision.reason()).isEqualTo("EXPLICIT_HUMAN_REQUEST");
}
```

Cover all seven reasons, precedence, and the non-handoff path.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=HandoffPolicyTest,AgentOrchestratorHandoffTest' test
```

Expected: compilation failure for missing policy classes.

- [ ] **Step 3: Implement policy and runtime integration**

Use a pure policy with no Spring dependencies. Inject it into
`AgentOrchestrator`; replace ad hoc escalation checks with:

```java
HandoffDecision handoff = handoffPolicy.decide(
        handoffSignalExtractor.from(req, response));
boolean escalated = response.escalate() || handoff.handoff();
```

For parallel execution, evaluate all responses and escalate when any decision
requires handoff.

- [ ] **Step 4: Run targeted tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=HandoffPolicyTest,AgentOrchestratorHandoffTest,AgentOrchestratorTest' test
```

Expected: all handoff and existing orchestrator tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/agent EchoMindJava/src/test/java/com/echomind/agent
git commit -m "feat: add deterministic handoff policy"
```

---

### Task 3: Fixed-ID Idempotent Golden Knowledge Import

**Files:**
- Create: `EchoMindJava/src/main/java/com/echomind/saas/rag/application/KnowledgeIndexBuilder.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/rag/application/KnowledgeIngestionService.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenKnowledgeImporter.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/rag/application/KnowledgeIndexBuilderTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenKnowledgeImporterTest.java`

**Interfaces:**
- Produces: `IndexedKnowledge KnowledgeIndexBuilder.build(String documentId, int versionNumber, String title, String content, KnowledgeVisibility visibility, String customerId)`
- Produces: `void GoldenKnowledgeImporter.ensureImported(GoldenDataset dataset)`
- Requires: live `EmbeddingProvider`; throws `EmbeddingUnavailableException` when unavailable.

`IndexedKnowledge` contains normalized text, source checksum, model name, and
`List<KnowledgeChunk>`.

- [ ] **Step 1: Write failing deterministic-index tests**

```java
@Test
void buildsFixedChunkIdAndChecksumForGoldenDocument() {
    IndexedKnowledge indexed = builder.build(
            "golden-doc-refund-policy", 1, "退款政策",
            "# 退款政策\n\n退款申请在五个工作日内处理。",
            KnowledgeVisibility.TENANT, null);
    assertThat(indexed.chunks()).singleElement()
            .extracting(KnowledgeChunk::chunkId)
            .isEqualTo("chunk-2e8259f26c55c93d5de365509f7b4eab");
}
```

- [ ] **Step 2: Write failing importer idempotency test**

The test starts MySQL, imports the dataset twice, and asserts:

```java
assertThat(firstDocumentCount).isEqualTo(12);
assertThat(firstChunkCount).isEqualTo(12);
assertThat(secondDocumentCount).isEqualTo(firstDocumentCount);
assertThat(secondChunkCount).isEqualTo(firstChunkCount);
```

Also assert every version is `PUBLISHED`, version is `1`, and returned chunk IDs
equal the dataset expectations.

- [ ] **Step 3: Run failing tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=KnowledgeIndexBuilderTest,GoldenKnowledgeImporterTest' test
```

Expected: missing builder/importer failures.

- [ ] **Step 4: Refactor indexing and implement importer**

Move normalization, checksum generation, chunk construction, and deterministic
chunk IDs from `KnowledgeIngestionService` into `KnowledgeIndexBuilder`.

The importer behavior is:

1. Require `embeddings.available()`.
2. Build each fixed document with its committed version and chunk IDs.
3. If absent, insert document/version/chunks and publish.
4. If present, verify source checksum, embedding model, chunk count, chunk IDs,
   chunk checksums, ACL, and `PUBLISHED` status.
5. On mismatch, fail without mutating the existing row.

- [ ] **Step 5: Run repository and RAG tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=KnowledgeIndexBuilderTest,GoldenKnowledgeImporterTest,KnowledgeIngestionServiceTest,JdbcKnowledgeDocumentRepositoryTest,KnowledgeRagEndToEndTest' test
```

Expected: all pass with zero skips.

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/rag/application EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden EchoMindJava/src/test/java/com/echomind/saas/rag/application EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden
git commit -m "feat: import golden knowledge with fixed ids"
```

---

### Task 4: Retrieval, Handoff, And Report Evaluation

**Files:**
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenRagEvaluator.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/HandoffEvaluator.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReport.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriter.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationService.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenRagEvaluatorTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/HandoffEvaluatorTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriterTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationServiceTest.java`

**Interfaces:**
- Consumes: `GoldenDatasetLoader`, `GoldenKnowledgeImporter`, `KnowledgeRetrieval`, `HandoffPolicy`.
- Produces: `GoldenEvaluationReport GoldenEvaluationService.run(String datasetVersion, String jobId)`.
- Produces report sections: `retrievalMetrics`, `handoffMetrics`, `cases`, `limitations`.
- Writes Markdown to `data/eval/reports/<jobId>-rag-support-golden-v1.md`.

Metric signatures:

```java
record RetrievalMetrics(
        double recallAt1, double recallAt3, double recallAt5,
        double mrrAt5, double documentHitRate, double versionHitRate,
        double chunkHitRate, double citationHitRate,
        int aclViolations, double degradedRate,
        long p50LatencyMs, long p95LatencyMs) {}

record HandoffMetrics(
        double accuracy, double precision, double recall,
        double falseEscalationRate, double missedHandoffRate,
        int truePositive, int falsePositive,
        int trueNegative, int falseNegative) {}
```

- [ ] **Step 1: Write retrieval metric tests**

Use synthetic results to verify Recall@1/3/5, MRR, version and citation hits,
ACL violation detection, degradation rate, and P95 calculation.

- [ ] **Step 2: Write handoff metric tests**

Verify:

```text
TP=4 FP=1 TN=5 FN=1
accuracy=0.8182
precision=0.8
recall=0.8
falseEscalationRate=0.1667
missedHandoffRate=0.1667
```

- [ ] **Step 3: Run failing tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenRagEvaluatorTest,HandoffEvaluatorTest,GoldenEvaluationReportWriterTest,GoldenEvaluationServiceTest' test
```

Expected: missing classes or assertion failures.

- [ ] **Step 4: Implement evaluators and report assembly**

For every retrieval case:

1. Resolve the fixed actor.
2. Call `KnowledgeRetrieval.search(query, 5, actor)`.
3. Locate expected chunk in hits and citations.
4. Record latency, degradation modes, ACL violations, and per-case pass flags.

For every handoff case, call `HandoffPolicy.decide(signals)`.

Calculate DEV, HOLDOUT, and ALL metrics separately. Add limitations verbatim:

```text
Synthetic dataset; no human review or statistical representativeness claim.
Policy-level handoff evaluation; no external LLM calls.
```

- [ ] **Step 5: Implement JSON and Markdown report writer**

JSON must include dataset digest, `code_sha` from `GITHUB_SHA` or
`ECHOMIND_CODE_SHA`, model name, provenance, metrics, cases, and limitations.
Write Markdown atomically using a temporary file plus move.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenRagEvaluatorTest,HandoffEvaluatorTest,GoldenEvaluationReportWriterTest,GoldenEvaluationServiceTest,GoldenKnowledgeImporterTest' test
```

Expected: all pass and the generated report contains 50 cases.

- [ ] **Step 7: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden
git commit -m "feat: evaluate golden rag and handoff metrics"
```

---

### Task 5: Async API And Markdown Report Access

**Files:**
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/api/GoldenRagEvaluationRequest.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/evaluation/EvaluationJobService.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/api/EchoMindController.java`
- Test: `EchoMindJava/src/test/java/com/echomind/api/GoldenRagEvaluationControllerTest.java`
- Test: `EchoMindJava/src/test/java/com/echomind/evaluation/EvaluationJobServiceTest.java`

**Interfaces:**
- Produces: `POST /eval/golden-rag/jobs` returning `202 Accepted` and `EvaluationJob`.
- Produces: `GET /eval/jobs/{jobId}/report.md` returning `text/markdown`.
- Produces: `EvaluationJobService.submitGoldenRag(String datasetVersion, ActorScope actor)`.
- Produces: `String EvaluationJobService.markdownReport(String jobId, ActorScope actor)`.

Request:

```json
{
  "dataset_version": "rag-support-golden-v1"
}
```

- [ ] **Step 1: Write failing API tests**

Tests must cover:

- Tenant admin gets `202`.
- Non-admin gets `403`.
- Unknown dataset returns a job that fails with a safe error.
- Another tenant/user cannot read the job or Markdown.
- Successful job exposes dataset version, digest, DEV/HOLDOUT/ALL metrics, and
  the Markdown download route.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenRagEvaluationControllerTest,EvaluationJobServiceTest' test
```

Expected: missing endpoint/request/service methods.

- [ ] **Step 3: Extend async jobs**

Add a golden job kind to the file-backed evaluation job store. Persist the
generated Markdown path internally but do not expose arbitrary filesystem paths.

The executor calls:

```java
GoldenEvaluationReport report = goldenEvaluationService.run(
        request.datasetVersion(), jobId);
job.put("report", report.asMap());
job.put("report_markdown_path", report.markdownPath());
```

Do not block the HTTP request on dataset import or evaluation.

- [ ] **Step 4: Add controller endpoints and safe error mapping**

Use `TENANT_ADMIN` authorization. Map integrity and embedding failures to stable
job errors without leaking local paths, class names, SQL, or model details.

- [ ] **Step 5: Run API tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenRagEvaluationControllerTest,EvaluationJobServiceTest,AuthenticatedActorBoundaryTest' test
```

Expected: all pass.

- [ ] **Step 6: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/evaluation/api EchoMindJava/src/main/java/com/echomind/evaluation/EvaluationJobService.java EchoMindJava/src/main/java/com/echomind/api/EchoMindController.java EchoMindJava/src/test/java/com/echomind/api EchoMindJava/src/test/java/com/echomind/evaluation
git commit -m "feat: expose golden rag evaluation reports"
```

---

### Task 6: Frontend Golden Evaluation Report

**Files:**
- Modify: `EchoMindFrontend/src/App.vue`
- Modify: `EchoMindFrontend/src/lib/backends.js`
- Modify: `EchoMindFrontend/src/lib/auth.test.js`
- Test: `EchoMindFrontend/src/lib/goldenEvaluation.test.js`
- Modify: `EchoMindFrontend/package.json`

**Interfaces:**
- Consumes: `POST /eval/golden-rag/jobs`.
- Consumes: `GET /eval/jobs/{jobId}`.
- Consumes: `GET /eval/jobs/{jobId}/report.md`.
- Produces UI actions: run Golden RAG evaluation and download Markdown report.

- [ ] **Step 1: Write failing frontend tests**

Cover request payload:

```javascript
assert.deepEqual(body, { dataset_version: 'rag-support-golden-v1' });
```

Cover rendering of dataset version, digest, synthetic flags, DEV/HOLDOUT
metrics, failed cases, and the Markdown report URL.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
npm test -- --run
```

Expected: new golden evaluation tests fail.

- [ ] **Step 3: Implement frontend integration**

Add:

- One “运行 Golden RAG 评测” button.
- Dataset metadata panel.
- Retrieval metrics table.
- Handoff confusion matrix.
- Failed-case list.
- Authenticated Markdown download.

Keep existing evaluation job behavior unchanged.

- [ ] **Step 4: Run frontend tests and production build**

Run:

```powershell
npm test -- --run
npm run build
```

Expected: all tests pass and Vite build succeeds.

- [ ] **Step 5: Commit**

```powershell
git add -- EchoMindFrontend/src/App.vue EchoMindFrontend/src/lib EchoMindFrontend/package.json
git commit -m "feat: show golden rag evaluation reports"
```

---

### Task 7: Operations Documentation And Full Verification

**Files:**
- Create: `docs/experiments/GOLDEN_RAG_HANDOFF_V1.md`
- Modify: `docs/LOCAL_DEVELOPMENT.md`
- Modify: `EchoMindJava/README.md`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-11-18-golden-rag-handoff-evaluation.md`

**Interfaces:**
- Documents dataset provenance, split policy, report endpoint, metrics, and
  limitations.

- [ ] **Step 1: Write the experiment report**

Include:

- Dataset version and digest.
- File inventory.
- 30/20 case split.
- Zero-token proof.
- Commands and actual outputs.
- Baseline metrics.
- Bugs and fixes encountered.
- Explicit synthetic-data limitations.

- [ ] **Step 2: Run focused and full verification**

Run:

```powershell
.\mvnw.cmd '-Dtest=GoldenDatasetLoaderTest,KnowledgeIndexBuilderTest,GoldenKnowledgeImporterTest,HandoffPolicyTest,AgentOrchestratorHandoffTest,GoldenRagEvaluatorTest,HandoffEvaluatorTest,GoldenEvaluationReportWriterTest,GoldenEvaluationServiceTest,GoldenRagEvaluationControllerTest,KnowledgeRagEndToEndTest' test
.\mvnw.cmd -q test
```

Expected: zero failures, errors, or skips.

Run:

```powershell
cd EchoMindFrontend
npm test -- --run
npm run build
```

Expected: all tests pass and production build succeeds.

- [ ] **Step 3: Verify report artifacts**

Assert:

```text
dataset_version=rag-support-golden-v1
retrieval_cases=30
handoff_cases=20
external_llm_tokens=0
provenance=synthetic
human_reviewed=false
```

Verify repeated evaluation produces no additional knowledge rows.

- [ ] **Step 4: Run repository checks**

Run:

```powershell
git diff --check
git status --short --branch
```

Expected: no whitespace errors.

- [ ] **Step 5: Commit final documentation**

```powershell
git add -- docs README.md EchoMindJava/README.md
git commit -m "docs: record golden rag evaluation"
```

## Self-Review Checklist

- Dataset counts and splits: Task 1.
- Fixed document/version/chunk IDs and checksums: Task 1 and Task 3.
- Dedicated tenant and idempotent import: Task 3.
- Deterministic handoff policy: Task 2.
- Retrieval and handoff metrics: Task 4.
- JSON and Markdown reports: Task 4.
- Async report API and authorization: Task 5.
- Frontend report surface: Task 6.
- Zero external LLM tokens and synthetic limitations: Tasks 1, 4, 5, and 7.
- Full regression and documentation: Task 7.
