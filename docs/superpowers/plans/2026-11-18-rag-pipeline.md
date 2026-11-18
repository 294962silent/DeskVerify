# RAG Pipeline Implementation Plan

## Goal

Implement tenant-scoped knowledge document versioning, deterministic chunking,
MySQL-backed lexical retrieval, local semantic retrieval, RRF fusion, reranking,
and structured citations without changing the existing Ticket workflow.

## Task 1: Durable Document Model

- Add `knowledge_document`, `knowledge_document_version`, and `knowledge_chunk`.
- Keep immutable versions and at most one published version per document.
- Scope every key and query by `tenant_id`.
- Store chunk ranges, heading path, visibility, customer ACL, checksum, and embedding JSON.
- Add MySQL ngram FULLTEXT indexes for lexical retrieval.

## Task 2: Ingestion And Chunking

- Normalize line endings and whitespace.
- Chunk with bounded size and overlap while preserving Markdown section context.
- Generate deterministic checksums and local hashing embeddings.
- Insert version and chunks transactionally.
- Publish only after chunk-count and model metadata verification.
- Retire the previous version only after the replacement is published.

## Task 3: Hybrid Retrieval

- Apply tenant and customer visibility filters before retrieval.
- Run lexical and vector retrieval independently.
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

## Acceptance

- Duplicate content for the same document version is deduplicated by checksum.
- Failed or unpublished versions never become retrieval candidates.
- Retrieval always includes tenant scope and customer ACL filtering.
- RRF and rerank ordering are deterministic.
- Every returned evidence item maps to an active chunk and citation.
- Backend and frontend suites pass with zero skips.

## Verification

- Backend full suite: 223 tests, 0 failures/errors/skips.
- Live MySQL 8.4 migration V1-V10 and knowledge E2E passed.
- Frontend: 17 tests passed; production build succeeded.
- The default embedding adapter is a deterministic local hashing fallback. A real
  `bge-small-zh-v1.5` ONNX adapter is not included and must not be claimed as active.
