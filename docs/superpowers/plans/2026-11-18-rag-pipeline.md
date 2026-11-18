# RAG Pipeline Implementation Plan

## Goal

Implement tenant-scoped knowledge document versioning, deterministic chunking,
MySQL-backed lexical retrieval, local `bge-small-zh-v1.5` ONNX semantic
retrieval, RRF fusion, reranking, and structured citations without changing the
existing Ticket workflow.

## Task 1: Durable Document Model

- Add `knowledge_document`, `knowledge_document_version`, and `knowledge_chunk`.
- Keep immutable versions and at most one published version per document.
- Scope every key and query by `tenant_id`.
- Store chunk ranges, heading path, visibility, customer ACL, checksum, and embedding JSON.
- Add MySQL ngram FULLTEXT indexes for lexical retrieval.

## Task 2: Ingestion And Chunking

- Normalize line endings and whitespace.
- Chunk with bounded size and overlap while preserving Markdown section context.
- Generate deterministic source checksums scoped by embedding model.
- Encode chunks with local `bge-small-zh-v1.5` FP32 ONNX CLS embeddings.
- Insert version and chunks transactionally.
- Publish only after chunk-count and model metadata verification.
- Retire the previous version only after the replacement is published.

## Task 3: Hybrid Retrieval

- Apply tenant and customer visibility filters before retrieval.
- Run lexical and vector retrieval independently.
- Load vectors only when their version embedding model matches the active model.
- Report `embedding_model_mismatch` when active documents still use another model.
- Fuse rank lists with Reciprocal Rank Fusion.
- Rerank fused candidates with deterministic cross-feature scoring.
- Return a degraded mode when a retrieval leg fails.

## Task 4: Citations And HTTP

- Assemble citations from the active chunk version only.
- Expose create-version, publish, search, and stats endpoints.
- Return document title, version, page/section, heading path, excerpt, and character range.
- Verify the search response never exposes another tenant's document.

## Task 5: Frontend And Verification

- Use the versioned Java RAG APIs from the knowledge workspace.
- Show retrieval mode, scores, and citation metadata.
- Run focused unit tests, live MySQL tests, frontend tests/build, and full backend regression.

## Task 6: Legacy Hash Embedding Migration

- Add a tenant-admin migration API that scans stale active `PUBLISHED` versions.
- Support `dry_run`, bounded pages, and a stable `after_document_id` cursor.
- Lock each document, verify source and target ACL/chunk counts, reuse or create the
  current-model DRAFT, and publish it in one transaction.
- Continue after per-document failures and return `PLANNED`, `MIGRATED`, `SKIPPED`,
  or `FAILED` items.
- Make reruns idempotent and return `scanned=0` after migration.
- Return stable `503 EMBEDDING_UNAVAILABLE` when live migration cannot load the model.

## Acceptance

- Duplicate content for the same document version is deduplicated by checksum.
- Failed or unpublished versions never become retrieval candidates.
- Retrieval always includes tenant scope and customer ACL filtering.
- RRF and rerank ordering are deterministic.
- Every returned evidence item maps to an active chunk and citation.
- The runtime default uses the real BGE ONNX provider; hash embeddings exist only
  as a test fixture and are not a production fallback.
- Re-indexing unchanged content under a different embedding model creates a new
  version instead of reusing an incompatible checksum match.
- Backend and frontend suites pass; with model files present, backend has zero skips.

## Verification

- Legacy-migration focused suite: 24 tests, 0 failures/errors/skips.
- Backend full suite: 246 tests, 0 failures/errors/skips.
- Live MySQL 8.4 migration V1-V11, demo V100, and knowledge E2E passed.
- Frontend: 17 tests passed; production build succeeded.
- The default embedding adapter is real `bge-small-zh-v1.5` FP32 ONNX:
  - ModelScope source:
    `https://modelscope.cn/models/lyf219/bge-small-zh-v1.5-onnx`
  - `model.onnx` SHA-256:
    `e256a606233996478f0eb6c99c9814e3797043d4a5563b4646b10929546196e5`
  - `tokenizer.json` SHA-256:
    `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26`
- Model files are downloaded by
  `EchoMindJava/scripts/download-bge-small-zh-v1.5.ps1` and mounted/copied for
  deployment; they are intentionally excluded from Git.
- Missing model or inference failures produce explicit lexical-only degradation.
- Legacy hash documents can be re-indexed through
  `POST /api/v1/knowledge/embedding-migrations`; a second run finds no stale versions.
- Docker image `echomind-java:bge-verified` built successfully after configuring
  Docker Desktop to use the reachable local system proxy.
- Container verification confirmed the copied model hashes, Linux tokenizer
  native loading, BGE ONNX provider initialization, 512-dimensional persisted
  vectors, and a published-document citation without degraded modes.
