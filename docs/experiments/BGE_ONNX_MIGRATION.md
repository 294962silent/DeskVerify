# BGE Small Chinese ONNX Migration

Date: 2026-11-18

Branch: `codex/echomind-saas-mvp`

## Goal

Replace the runtime `local-hash-char-ngram-v1` embedding fallback with a real
`bge-small-zh-v1.5` FP32 ONNX provider while preserving:

- MySQL ngram lexical recall
- vector recall and Reciprocal Rank Fusion
- `embedding_unavailable` and `vector_unavailable` degradation
- structured `/chat` citations
- tenant and customer ACL filtering

## Final Design

- Runtime provider:
  `OnnxBgeEmbeddingProvider`.
- Model: BGE small Chinese v1.5, FP32 ONNX, hidden size 512, max sequence 512.
- Pooling: BERT CLS token followed by L2 normalization.
- Document text: encoded without an instruction prefix.
- Query text: prefixed with
  `为这个句子生成表示以用于检索相关文章：`.
- Vector rows are selected only when their published version has the active
  embedding model name.
- The old hash implementation is now a package-private test fixture under
  `src/test`; it is not a Spring bean or runtime fallback.

## Model Provenance

Source:

```text
https://modelscope.cn/models/lyf219/bge-small-zh-v1.5-onnx
```

Download and verification:

```powershell
cd E:\EchoMind\echomind-saas-agent\EchoMindJava
.\scripts\download-bge-small-zh-v1.5.ps1
```

Verified SHA-256:

| File | SHA-256 |
| --- | --- |
| `model.onnx` | `e256a606233996478f0eb6c99c9814e3797043d4a5563b4646b10929546196e5` |
| `tokenizer.json` | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |
| `vocab.txt` | `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c` |

`model.onnx` is 94,835,369 bytes. The model directory is Git-ignored and must be
downloaded before running locally or building the Docker image.

## Bugs And Fixes

### 1. Hash Embedding Was Not a Semantic Model

Observed:

- The previous default produced deterministic character n-gram vectors.
- It did not use the BGE tokenizer, model weights, query instruction, CLS
  pooling, or a real semantic representation.

Cause:

- The adapter implemented a local hash embedding and was directly registered as
  the Spring component.

Fix:

- Added ONNX Runtime and DJL Hugging Face tokenizer dependencies.
- Added the real ONNX provider and made it the only runtime
  `EmbeddingProvider`.
- Moved the hash provider to test sources.

Verification:

- `OnnxBgeEmbeddingProviderTest` checks 512 dimensions, deterministic output,
  unit L2 norm, and related-query ranking above an unrelated query.

### 2. Same Content Could Not Be Re-Indexed After a Model Change

Observed during migration audit:

- Version lookup and the database unique key used only
  `tenant + document + source_checksum`.
- Re-submitting unchanged content after switching from hash to BGE returned the
  old version instead of producing vectors for the new model.

Cause:

- Embedding model was not part of checksum deduplication identity.

Fix:

- V11 replaces the checksum unique key with
  `tenant_id + document_id + source_checksum + embedding_model`.
- Ingestion lookup now includes the active embedding model.
- Added a MySQL repository test that re-indexes the same source checksum under a
  second model.

### 3. Old-Model Vectors Were Skipped Silently

Observed during migration audit:

- Retrieval correctly selected only vectors matching the active model.
- If all active documents still used hash embeddings, the response had no vector
  hits but also no marker explaining why.

Cause:

- An empty model-filtered result was indistinguishable from an empty knowledge
  base.

Fix:

- Added a tenant/ACL-scoped count of active chunks from other embedding models.
- Added `embedding_model_mismatch`.
- When no compatible vector exists, the response also contains
  `vector_unavailable`; lexical retrieval and citations remain available.
- When some compatible vectors exist, vector recall still runs and the response
  reports partial model coverage through `embedding_model_mismatch`.

### 4. Unit Test Compared the Wrong Checksum

Observed:

```text
Argument(s) are different!
Wanted source checksum: aaaaaaaaa...aaaaaaaa
Actual source checksum: c95e7e82...e2117
```

Cause:

- The test verified the checksum stored on its mock return object instead of the
  checksum computed from the submitted content.

Fix:

- Kept the business assertion focused on tenant, document ID, and active
  embedding model, and used an arbitrary checksum matcher.

Verification:

- `KnowledgeIngestionServiceTest`: 4 tests passed.

### 5. Docker Image Did Not Contain the Ignored Model

Observed during deployment review:

- `models/` is Git-ignored.
- The old Dockerfile copied only the JAR, so a container built from this branch
  would start with no BGE files and silently run lexical-only retrieval.

Cause:

- No explicit model provisioning step existed in the image.

Fix:

- Docker build now requires and copies
  `models/bge-small-zh-v1.5`.
- Dockerfile exports absolute model and tokenizer paths.
- README requires the download script before `docker compose build`.

### 6. ONNX Tests Assumed the Git-Ignored Model Exists

Observed:

- A fresh checkout has no model files, so mandatory ONNX tests would fail before
  exercising Java behavior.

Cause:

- Test fixtures were written against locally downloaded binary assets.

Fix:

- Real-model assertions are guarded by assumptions on the two required files.
- A separate missing-model test verifies `available() == false` and
  `EmbeddingUnavailableException` without requiring the binary.
- Local CI-style verification still requires the model download for zero skips.

### 7. Docker Hub Was Unreachable Through Direct DNS/TCP

Observed:

```text
failed to fetch oauth token:
dial tcp ...:443: connectex:
connection attempt failed because the connected party did not properly respond
```

Cause:

- Direct access to `auth.docker.io` timed out.
- DNS returned unrelated-looking IPv4 addresses for Docker Hub domains.
- Docker Desktop was configured for automatic/system proxy behavior, while the
  only verified path was the local Clash Verge proxy at `127.0.0.1:7897`.
- The failure happened during base-image metadata resolution, before source
  compilation or BGE model copy.

Diagnosis:

```text
direct curl: connection timed out after 20 seconds
proxy curl : HTTP 200 through 127.0.0.1:7897
```

Fix:

- Backed up
  `C:\Users\MLL\AppData\Roaming\Docker\settings-store.json`.
- Stopped Docker Desktop.
- Set these Docker Desktop settings:

```json
{
  "ProxyHTTPMode": "manual",
  "OverrideProxyHTTP": "http://127.0.0.1:7897",
  "OverrideProxyHTTPS": "http://127.0.0.1:7897",
  "OverrideProxyExclude": "localhost,127.0.0.1,::1,hubproxy.docker.internal"
}
```

- Restarted Docker Desktop.
- Verified `httpproxy.log` reported:

```text
host will use proxy: app settings http_proxy=http://127.0.0.1:7897 ...
Linux will use proxy: app settings http_proxy=http://127.0.0.1:7897 ...
```

- Pulled both base images through the repaired path, then built
  `echomind-java:bge-verified`.
- Re-ran a normal `docker build` without `--pull=false`; BuildKit completed
  successfully.

Verification:

```text
image: echomind-java:bge-verified
image id: sha256:4802d29a2333f02bedc26682770cb42025a205fa467e789f739ed1318408531a
image size: 895 MB
model.onnx SHA-256:
e256a606233996478f0eb6c99c9814e3797043d4a5563b4646b10929546196e5
tokenizer.json SHA-256:
48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26
```

The one-off container loaded the Linux tokenizer native library and logged:

```text
Extracting native/lib/linux-x86_64/cpu/libtokenizers.so to cache
Loaded BGE ONNX embedding model=bge-small-zh-v1.5-onnx-fp32
path=/app/models/bge-small-zh-v1.5/model.onnx maxLength=512
```

After the final publish-lock change, the image was rebuilt and the same
container-level reflection smoke again loaded the Linux tokenizer native
library and printed:

```text
model=bge-small-zh-v1.5-onnx-fp32 dimensions=512
```

The smoke workflow created and published a document, then searched for
`退款多久到账`:

```text
embedding model: bge-small-zh-v1.5-onnx-fp32
stored vector dimensions: 512
published status: PUBLISHED
hits: 1
citations: 1
degraded modes: none
```

During the first smoke attempt, the follow-up publish request returned
`AUTHENTICATION_REQUIRED`. The container was healthy and the document had already
been created as `DRAFT`; the verification script had parsed the create response
incorrectly, leaving an empty document ID and constructing the wrong route. The
script then read the real ID from MySQL and repeated publish/search successfully.
This was a smoke-script parsing issue, not an application authentication defect.

### 8. Publish Paths Used Different Lock Order

Observed during concurrency review:

- The migration worker already locked `knowledge_document` before re-indexing.
- The ordinary publish path locked a version row first and only then updated the
  document's current version.
- A concurrent ordinary publish and migration could therefore enter the publish
  sequence with different lock order. The behavior had no direct regression
  test.

Cause:

- Publication invariants were enforced by status updates and transactions, but
  the document-level mutex was not the common first lock for every publish
  caller.

Fix:

- `JdbcKnowledgeDocumentRepository.publish(...)` now locks
  `knowledge_document` before locking and validating the target version.
- Both migration and ordinary publication now share the same first lock.

Verification:

- Added a MySQL concurrency test that holds the document row lock in one
  transaction and starts publication in another.
- The publication future remains blocked until the first transaction releases
  the row, then switches the document to exactly one published target version.

## Legacy Hash Reindex Migration

### Workflow

`KnowledgeEmbeddingMigrationService` scans only the current tenant's active
`PUBLISHED` versions whose embedding model differs from the active BGE model.
Clients can first run a dry-run, then execute bounded pages with a stable
`after_document_id` cursor.

For each document, `KnowledgeEmbeddingMigrationWorker` runs in one transaction:

1. Lock the `knowledge_document` row.
2. Verify the candidate is still the current source version.
3. Read the source chunks' ACL summary and require exactly one visibility scope.
4. Verify the summarized chunk count matches the indexed version count.
5. Reuse an existing current-model DRAFT or create a new BGE version from the
   immutable normalized text.
6. Verify the target chunk count and ACL match the source.
7. Publish the target version and retire the legacy source atomically.

Per-document failures are returned as `FAILED` while later candidates continue.
Running the endpoint again after successful migration returns `scanned=0` and
`remaining=0`.

### Safety Decisions

- A stale scan is always tenant-scoped. Customer request bodies cannot select a
  different tenant.
- The source ACL is derived from existing chunks, not trusted from migration input.
- Mixed source ACLs are rejected instead of guessing.
- A reused DRAFT is published only when its ACL and chunk count still match the
  source.
- The document row is locked so a concurrent newer publish cannot silently be
  overwritten by a stale migration candidate.
- With `dry_run=true`, no model load, write, or publish occurs.

### Migration Tests

Unit and HTTP coverage:

```text
KnowledgeEmbeddingMigrationServiceTest
KnowledgeEmbeddingMigrationWorkerTest
KnowledgeRagControllerTest
15 tests passed
```

MySQL and real-BGE coverage:

```text
JdbcKnowledgeDocumentRepositoryTest
KnowledgeRagEndToEndTest
9 tests passed
```

The real E2E test seeds a legacy hash version, previews it with `dry_run`, migrates
and publishes it, verifies the old version is `RETIRED`, checks that the stored BGE
vector has 512 dimensions, reruns the migration with no work remaining, and confirms
customer search returns the BGE version and citation without degraded modes.

The repository test also verifies that publication waits for the document row lock
and that concurrent publication paths cannot leave more than one published version.

## Verification Evidence

### Baseline After ONNX Adapter, Before Migration Fixes

```text
Backend full suite: 226 tests, 0 failures, 0 errors, 0 skips
Frontend: 17 tests passed
Vite production build: passed
```

### Migration-Focused Verification

Runs:

```powershell
.\mvnw.cmd '-Dtest=OnnxBgeEmbeddingProviderTest,KnowledgeRetrievalServiceTest,KnowledgeIngestionServiceTest,JdbcKnowledgeDocumentRepositoryTest,TicketSchemaTest' test
```

Result:

```text
19 tests, 0 failures, 0 errors, 0 skips
```

The run confirmed:

- V1-V11 migrate on MySQL 8.4.
- Same content can be indexed under a different embedding model.
- Old-model chunks are counted through tenant/customer ACL.
- Missing ONNX files produce explicit unavailable state.
- Real BGE inference produces normalized 512-dimensional vectors.

### Test Infrastructure Observation

The baseline full suite repeatedly logged transient Hikari
`Communications link failure` warnings while Testcontainers instances were being
created or torn down. The suite still completed with 226 passing tests. This was
not reproduced as a product failure, but it should be watched for future suite
flakiness.

### Final Migration Verification

```text
Backend full suite: 246 tests, 0 failures, 0 errors, 0 skips
Legacy-migration focused suite: 24 tests, 0 failures, 0 errors, 0 skips
Frontend: 17 tests passed
Vite production build: passed
Flyway V1-V11 and demo V100: passed on MySQL 8.4
Docker image build and BGE container smoke: passed
```

## Remaining Boundaries

- Existing hash-model PUBLISHED documents can be migrated through the reindex API;
  they are not rewritten in place. Until migrated, they remain usable by lexical
  recall and are marked `embedding_model_mismatch`.
- Vector scoring currently loads compatible active chunk vectors and computes
  cosine similarity in Java. This is suitable for the current MVP, not a
  substitute for a production ANN index at large scale.
- The ONNX model package comes from a ModelScope conversion of the BAAI model.
  Model metadata, checksums, dimensions, and semantic smoke tests were verified;
  a full Recall@K benchmark was not run in this change.
- The model binary is intentionally not committed. CI and deployment must run the
  download script or mount an equivalent verified model directory.
