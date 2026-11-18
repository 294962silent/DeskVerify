# Golden Release Gate And CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the versioned Golden RAG/handoff evaluation into an enforced release gate and run the real MySQL + BGE path in GitHub Actions with generated report artifacts.

**Architecture:** A versioned baseline JSON defines the accepted retrieval and handoff metrics. A deterministic gate evaluator compares the current report with that baseline, emits blocking and advisory checks, and is embedded in the report. The async Golden job succeeds only when all blocking checks pass. CI verifies that the BGE model is present, runs the backend suite, asserts that the Golden E2E test executed without skips, validates the generated gate result, and uploads JSON/Markdown reports.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, MySQL 8.4 Testcontainers, BGE ONNX, Node 22, Vite, PowerShell, GitHub Actions.

## Global Constraints

- External LLM token budget for Golden V1 remains exactly `0`.
- Golden evaluation must use real MySQL 8.4 and `bge-small-zh-v1.5-onnx-fp32`, not hash or fake embeddings.
- Dataset version is `rag-support-golden-v1`.
- Dataset SHA-256 is `d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968`.
- ACL violations are always blocking and the configured maximum defaults to `0`.
- Recall@5, MRR@5, and citation-hit regression tolerance defaults to `0.02` against the versioned baseline.
- Handoff recall minimum defaults to `0.95`; false-escalation and missed-handoff maxima default to `0.10`.
- P95 latency defaults to a `2000 ms` advisory threshold; it is reported but does not block this small benchmark.
- Planned cases, human review, and external-call failures may never be converted into success.
- The synthetic dataset remains explicitly non-human-reviewed and non-representative.
- Do not commit the generated Maven Wrapper JAR or BGE model binaries.
- Do not use subagents.

---

### Task 1: Add Baseline Loading And Gate Evaluation

**Files:**
- Create: `EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/baseline.json`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationBaseline.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationBaselineLoader.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGate.java`
- Create: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGateEvaluator.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/config/EchoMindProperties.java`
- Modify: `EchoMindJava/src/main/resources/application.yml`
- Test: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGateEvaluatorTest.java`

**Interfaces:**
- Produces: `GoldenEvaluationBaselineLoader.load(String datasetVersion): GoldenEvaluationBaseline`
- Produces: `GoldenEvaluationGateEvaluator.evaluate(String datasetVersion, String datasetSha256, Map<String, GoldenEvaluationReport.RetrievalMetrics> retrievalMetrics, Map<String, GoldenEvaluationReport.HandoffMetrics> handoffMetrics): GoldenEvaluationGate`
- Produces: `GoldenEvaluationGate(boolean passed, List<GateCheck> checks, Thresholds thresholds)`
- Produces: `GateCheck(String id, String split, boolean blocking, boolean passed, String expectation, String observed)`
- Produces: `Thresholds(double maxMetricDrop, int maxAclViolations, double maxDegradedRate, double minHandoffRecall, double maxFalseEscalationRate, double maxMissedHandoffRate, long p95LatencyAdvisoryMs)`

- [ ] **Step 1: Write the failing gate test**

```java
@Test
void passesWhenMetricsMeetTheVersionedBaseline() {
    EchoMindProperties properties = new EchoMindProperties();
    GoldenEvaluationBaselineLoader loader = mock(GoldenEvaluationBaselineLoader.class);
    when(loader.load(VERSION)).thenReturn(baseline());
    GoldenEvaluationGateEvaluator evaluator =
            new GoldenEvaluationGateEvaluator(loader, properties);

    GoldenEvaluationGate gate = evaluator.evaluate(
            VERSION, "a".repeat(64), perfectRetrieval(), perfectHandoff());

    assertThat(gate.passed()).isTrue();
    assertThat(gate.checks())
            .allMatch(check -> !check.blocking() || check.passed());
    assertThat(gate.checks())
            .extracting(GoldenEvaluationGate.GateCheck::id)
            .contains("baseline.dataset_sha256", "retrieval.DEV.recall_at5",
                    "retrieval.HOLDOUT.citation_hit_rate", "handoff.ALL.recall");
}

@Test
void failsWhenRecallOrAclRegresses() {
    GoldenEvaluationGate gate = evaluator.evaluate(
            VERSION,
            "a".repeat(64),
            Map.of("DEV", retrieval(0.97, 0, 0),
                    "HOLDOUT", retrieval(1.0, 0, 0),
                    "ALL", retrieval(0.97, 1, 0)),
            perfectHandoff());

    assertThat(gate.passed()).isFalse();
    assertThat(failedBlockingIds(gate))
            .contains("retrieval.DEV.recall_at5",
                    "retrieval.ALL.acl_violations");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\mvnw.cmd '-Dtest=GoldenEvaluationGateEvaluatorTest' test
```

Expected: compilation fails because the baseline/gate classes do not exist.

- [ ] **Step 3: Add the versioned baseline**

Use the observed V1 metrics, with the fixed dataset digest:

```json
{
  "dataset_version": "rag-support-golden-v1",
  "dataset_sha256": "d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968",
  "retrieval_metrics": {
    "DEV": {
      "recall_at1": 1.0,
      "recall_at3": 1.0,
      "recall_at5": 1.0,
      "mrr_at5": 1.0,
      "document_hit_rate": 1.0,
      "version_hit_rate": 1.0,
      "chunk_hit_rate": 1.0,
      "citation_hit_rate": 1.0,
      "acl_violations": 0,
      "degraded_rate": 0.0,
      "p50_latency_ms": 33,
      "p95_latency_ms": 40
    },
    "HOLDOUT": {
      "recall_at1": 1.0,
      "recall_at3": 1.0,
      "recall_at5": 1.0,
      "mrr_at5": 1.0,
      "document_hit_rate": 1.0,
      "version_hit_rate": 1.0,
      "chunk_hit_rate": 1.0,
      "citation_hit_rate": 1.0,
      "acl_violations": 0,
      "degraded_rate": 0.0,
      "p50_latency_ms": 33,
      "p95_latency_ms": 42
    }
  },
  "handoff_metrics": {
    "ALL": {
      "accuracy": 1.0,
      "precision": 1.0,
      "recall": 1.0,
      "false_escalation_rate": 0.0,
      "missed_handoff_rate": 0.0,
      "true_positive": 10,
      "false_positive": 0,
      "true_negative": 10,
      "false_negative": 0
    }
  }
}
```

- [ ] **Step 4: Implement baseline loading and immutable gate records**

`GoldenEvaluationBaselineLoader` must reject unsafe dataset versions, missing
resources, malformed JSON, and a baseline version mismatch with
`GoldenDatasetException`. `GoldenEvaluationGate` recalculates `passed` from
blocking checks in its compact constructor.

- [ ] **Step 5: Implement the comparisons**

For `DEV` and `HOLDOUT`, emit blocking checks for:

- `recall_at5 >= baseline - maxMetricDrop`
- `mrr_at5 >= baseline - maxMetricDrop`
- `citation_hit_rate >= baseline - maxMetricDrop`
- `acl_violations <= maxAclViolations`
- `degraded_rate <= maxDegradedRate`

For `ALL`, emit blocking handoff checks for:

- `recall >= minHandoffRecall`
- `false_escalation_rate <= maxFalseEscalationRate`
- `missed_handoff_rate <= maxMissedHandoffRate`

Emit a non-blocking P95 check using `p95LatencyAdvisoryMs`.

- [ ] **Step 6: Add configuration defaults**

Bind these under `echomind.eval.golden-gate` and expose matching environment
variables:

```yaml
golden-gate:
  max-metric-drop: ${GOLDEN_EVAL_MAX_METRIC_DROP:0.02}
  max-acl-violations: ${GOLDEN_EVAL_MAX_ACL_VIOLATIONS:0}
  max-degraded-rate: ${GOLDEN_EVAL_MAX_DEGRADED_RATE:0.0}
  min-handoff-recall: ${GOLDEN_EVAL_MIN_HANDOFF_RECALL:0.95}
  max-false-escalation-rate: ${GOLDEN_EVAL_MAX_FALSE_ESCALATION_RATE:0.10}
  max-missed-handoff-rate: ${GOLDEN_EVAL_MAX_MISSED_HANDOFF_RATE:0.10}
  p95-latency-advisory-ms: ${GOLDEN_EVAL_P95_ADVISORY_MS:2000}
```

- [ ] **Step 7: Run the gate tests and verify GREEN**

Run the focused command from Step 2. Expected: all gate tests pass.

- [ ] **Step 8: Commit**

```powershell
git add -- EchoMindJava/src/main/resources/eval/golden/rag-support-golden-v1/baseline.json EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationBaseline.java EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationBaselineLoader.java EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGate.java EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGateEvaluator.java EchoMindJava/src/main/java/com/echomind/config/EchoMindProperties.java EchoMindJava/src/main/resources/application.yml EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationGateEvaluatorTest.java
git commit -m "feat: add golden evaluation release gate"
```

---

### Task 2: Embed The Gate In Reports And Async Jobs

**Files:**
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReport.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationService.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriter.java`
- Modify: `EchoMindJava/src/main/java/com/echomind/evaluation/EvaluationJobService.java`
- Modify: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationServiceTest.java`
- Modify: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriterTest.java`
- Modify: `EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationEndToEndTest.java`
- Modify: `EchoMindJava/src/test/java/com/echomind/evaluation/EvaluationJobServiceTest.java`

**Interfaces:**
- Consumes: `GoldenEvaluationGateEvaluator.evaluate(...)`
- Produces: `GoldenEvaluationReport.gate(): GoldenEvaluationGate`
- Produces: failed async job error `Golden RAG evaluation failed (GOLDEN_EVALUATION_GATE_FAILED).`

- [ ] **Step 1: Extend async-job tests first**

Add a test where the service returns a report containing a failed gate. Assert
that the job is `failed`, the Markdown report remains downloadable, and the
report map contains `gate.passed=false`:

```java
@Test
void goldenRagJobFailsButKeepsReportWhenGateFails() throws Exception {
    Path markdown = tempDir.resolve("gate-report.md");
    Files.writeString(markdown, "# Failed gate\n");
    when(goldenEvaluationService.run(eq("rag-support-golden-v1"), anyString()))
            .thenReturn(goldenReport(markdown.toString(), failedGate()));

    EvaluationJob submitted = service.submitGoldenRag(
            "rag-support-golden-v1", owner);

    Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
        EvaluationJob loaded = service.get(submitted.jobId(), owner);
        assertThat(loaded.status()).isEqualTo("failed");
        assertThat(loaded.error()).isEqualTo(
                "Golden RAG evaluation failed (GOLDEN_EVALUATION_GATE_FAILED).");
        assertThat(loaded.report()).extracting("gate.passed").isEqualTo(false);
    });
    assertThat(service.markdownReport(submitted.jobId(), owner))
            .isEqualTo("# Failed gate\n");
}
```

- [ ] **Step 2: Run the job test and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dtest=EvaluationJobServiceTest#goldenRagJobFailsButKeepsReportWhenGateFails' test
```

Expected: compilation/test failure because reports do not carry a gate and job
completion ignores gate failure.

- [ ] **Step 3: Integrate the gate into `GoldenEvaluationService`**

Construct the gate after retrieval and handoff metrics are calculated, then put
`gate` immediately after `totals` in `GoldenEvaluationReport`.

- [ ] **Step 4: Render the gate in JSON and Markdown**

The Markdown report must include:

```markdown
## Release Gate

- Status: `PASSED`
- Blocking failures: `0`
- Advisory warnings: `0`

| Check | Split | Blocking | Result | Expected | Observed |
| --- | --- | --- | --- | --- | --- |
```

- [ ] **Step 5: Fail the job after persisting evidence**

Persist `report`, `report_download_path`, and `report_markdown_path` before
setting a gate-failed job to `failed`. The stable error code is
`GOLDEN_EVALUATION_GATE_FAILED`.

- [ ] **Step 6: Strengthen the real E2E assertion**

Assert `report.gate().passed()` and assert the Markdown contains
`## Release Gate` and `Status: `PASSED``.

- [ ] **Step 7: Run focused tests**

```powershell
.\mvnw.cmd '-Dtest=GoldenEvaluationServiceTest,GoldenEvaluationReportWriterTest,EvaluationJobServiceTest' test
.\mvnw.cmd '-Dtest=GoldenEvaluationEndToEndTest' test
```

Expected: all pass; Golden E2E executes with zero skips when model files exist.

- [ ] **Step 8: Commit**

```powershell
git add -- EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReport.java EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationService.java EchoMindJava/src/main/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriter.java EchoMindJava/src/main/java/com/echomind/evaluation/EvaluationJobService.java EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationServiceTest.java EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationReportWriterTest.java EchoMindJava/src/test/java/com/echomind/saas/evaluation/golden/GoldenEvaluationEndToEndTest.java EchoMindJava/src/test/java/com/echomind/evaluation/EvaluationJobServiceTest.java
git commit -m "feat: enforce golden gate in evaluation jobs"
```

---

### Task 3: Expose Gate Status In The Frontend

**Files:**
- Modify: `EchoMindFrontend/src/lib/goldenEvaluation.js`
- Modify: `EchoMindFrontend/src/lib/goldenEvaluation.test.js`
- Modify: `EchoMindFrontend/src/App.vue`

**Interfaces:**
- Produces normalized `gate: { passed, checks, thresholds }`
- Produces rendered gate status and failed blocking checks on `/monitor`

- [ ] **Step 1: Add a failing normalization test**

```javascript
test('golden report normalization preserves release gate checks', () => {
  const report = normalizeGoldenRagReport({
    dataset_version: 'rag-support-golden-v1',
    gate: {
      passed: false,
      checks: [{
        id: 'retrieval.DEV.recall_at5',
        split: 'DEV',
        blocking: true,
        passed: false,
        expectation: '>= 0.98',
        observed: '0.95',
      }],
      thresholds: { max_metric_drop: 0.02 },
    },
  })

  assert.equal(report.gate.passed, false)
  assert.equal(report.failedGateChecks.length, 1)
  assert.equal(report.failedGateChecks[0].id, 'retrieval.DEV.recall_at5')
})
```

- [ ] **Step 2: Run the frontend test and verify RED**

```powershell
cd ..\EchoMindFrontend
npm test -- --run
```

Expected: `report.gate` is undefined.

- [ ] **Step 3: Implement normalization and UI**

Display `Release gate: PASSED/FAILED`, blocking and advisory counts, and each
failed blocking check. Preserve the existing synthetic-data limitation text.

- [ ] **Step 4: Verify GREEN**

```powershell
npm test -- --run
npm run build
```

Expected: 21 tests pass; Vite build passes.

- [ ] **Step 5: Commit**

```powershell
git add -- EchoMindFrontend/src/lib/goldenEvaluation.js EchoMindFrontend/src/lib/goldenEvaluation.test.js EchoMindFrontend/src/App.vue
git commit -m "feat: show golden release gate status"
```

---

### Task 4: Add GitHub Actions And Artifact Validation

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `EchoMindJava/scripts/assert-golden-evaluation.ps1`
- Modify: `README.md`
- Modify: `EchoMindJava/README.md`
- Modify: `docs/LOCAL_DEVELOPMENT.md`

**Interfaces:**
- Produces CI checks `backend-and-frontend` and artifact `golden-evaluation-reports`
- Produces `assert-golden-evaluation.ps1 -ReportDirectory <path> -SurefireDirectory <path>`

- [ ] **Step 1: Add the CI assertion script**

The script must fail unless:

- exactly one Golden JSON report exists;
- `dataset_version` is `rag-support-golden-v1`;
- `gate.passed` is `true`;
- the report contains `gate.checks`;
- `GoldenEvaluationEndToEndTest` Surefire XML exists;
- its `tests` count is `1`;
- its `failures`, `errors`, and `skipped` counts are all `0`.

- [ ] **Step 2: Run the script locally**

```powershell
pwsh -NoProfile -File EchoMindJava/scripts/assert-golden-evaluation.ps1 `
  -ReportDirectory EchoMindJava/target/golden-e2e/reports `
  -SurefireDirectory EchoMindJava/target/surefire-reports
```

Expected: PASS after the E2E report exists.

- [ ] **Step 3: Add the workflow**

The workflow runs on `pull_request` and pushes to `main`, with:

- Java 21 Temurin and Maven cache;
- Node 22 and npm cache;
- Docker `mysql:8.4` pre-pull with retries;
- cached, SHA-256-verified BGE model download;
- backend `./mvnw -B -ntp test`;
- Golden artifact assertion;
- frontend `npm ci`, `npm test -- --run`, and `npm run build`;
- always-uploaded Surefire, Golden report, and Vite build artifacts;
- cancellation of superseded runs.

- [ ] **Step 4: Update documentation with exact thresholds and CI behavior**

Document that a Golden job with failed blocking checks is `failed` even though
the JSON/Markdown evidence remains downloadable. Document that P95 is advisory
for V1 and that synthetic metrics are not production claims.

- [ ] **Step 5: Validate YAML and script syntax**

```powershell
node -e "const fs=require('fs'); const s=fs.readFileSync('.github/workflows/ci.yml','utf8'); if (!s.includes('assert-golden-evaluation')) process.exit(1)"
pwsh -NoProfile -File EchoMindJava/scripts/assert-golden-evaluation.ps1 `
  -ReportDirectory EchoMindJava/target/golden-e2e/reports `
  -SurefireDirectory EchoMindJava/target/surefire-reports
```

Expected: both commands exit `0`.

- [ ] **Step 6: Commit and push**

```powershell
git add -- .github/workflows/ci.yml EchoMindJava/scripts/assert-golden-evaluation.ps1 README.md EchoMindJava/README.md docs/LOCAL_DEVELOPMENT.md
git commit -m "ci: enforce golden evaluation gate"
git push origin HEAD:refs/heads/codex/echomind-saas-mvp
```

---

### Task 5: Full Validation And Experiment Record

**Files:**
- Modify: `docs/experiments/GOLDEN_RAG_HANDOFF_V1.md`

**Interfaces:**
- Produces final local evidence and GitHub Actions run URL.

- [ ] **Step 1: Run the complete backend suite**

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\mvnw.cmd -q test
```

- [ ] **Step 2: Summarize Surefire rather than guessing**

```powershell
$reports = Get-ChildItem -LiteralPath 'target\surefire-reports' -Filter 'TEST-*.xml'
$totals = [ordered]@{tests=0; failures=0; errors=0; skipped=0}
foreach ($report in $reports) {
  [xml]$xml = Get-Content -LiteralPath $report.FullName
  $suite = $xml.testsuite
  $totals.tests += [int]$suite.tests
  $totals.failures += [int]$suite.failures
  $totals.errors += [int]$suite.errors
  $totals.skipped += [int]$suite.skipped
}
[pscustomobject]$totals | Format-List
```

- [ ] **Step 3: Run all frontend checks**

```powershell
cd ..\EchoMindFrontend
npm test -- --run
npm run build
```

- [ ] **Step 4: Record real evidence**

Append the exact test totals, Golden metrics, new bugs and fixes, CI run URL,
and remaining limitations to `docs/experiments/GOLDEN_RAG_HANDOFF_V1.md`.

- [ ] **Step 5: Commit and update the Draft PR**

```powershell
git add -- docs/experiments/GOLDEN_RAG_HANDOFF_V1.md
git commit -m "docs: record golden release gate verification"
git push origin HEAD:refs/heads/codex/echomind-saas-mvp
```

Expected: PR #1 updates, GitHub Actions completes, and the Golden report artifact
shows `gate.passed=true`.
