# DeskVerify Windows v0.1.4 Implementation Plan

**Goal:** Return grounded v2 citations with real BGE retrieval in chat with or
without a model key, and demonstrate recovery of an interrupted local worker.

**Architecture:** Keep the existing signed customer scope and hybrid RAG. Run
the Outbox worker as a separate Compose service; atomically record a
tenant-scoped MySQL Inbox receipt and complete the current fenced claim.

**Tech Stack:** Java 21, Spring Boot, MySQL 8.4, BGE ONNX, Docker Compose,
PowerShell 7, Ego Lite, GitHub Actions.

## Constraints

- One DS Max implements the changes in sequence; the release owner reviews and
  verifies them. Preserve customer ACLs, genuine degradation, and existing
  Windows volumes. Never write credentials into test output or release assets.
- Reuse the single MySQL database for local delivery receipts. No external
  broker, exactly-once external effect claims, or debug endpoints.
- Publish only after PR and merge-commit CI pass. Never move an older tag.

## RAG Acceptance

- [ ] Add failing cases for the no-key natural knowledge question and cold
  vector retrieval, then make them pass with visible positive vector evidence.
- [ ] On the official Windows package, publish a synthetic v2 as an isolated
  local admin. Run the same natural question in Ego Lite as a normal customer
  with and without a key. Require the answer and citation to agree with v2 and
  check evidence that the vector branch contributed in both runs.

## Worker Acceptance

- [ ] Verify claim expiry, stale completion rejection, and a single committed
  receipt with a real MySQL integration test.
- [ ] In an isolated Docker project, block acknowledgement with a test database
  transaction, observe the claim, and kill only the worker. Check the previous
  claim is not handled before expiry; require a replacement claim, successful
  completion, and exactly one receipt for the same event and tenant.

## Release Acceptance

- [ ] Run the Windows bootstrap and layout checks, frontend tests/build, and
  backend tests with Golden evaluation. Push a PR; wait for CI on the PR and
  the actual merge commit.
- [ ] Create annotated `v0.1.4` on the verified merge commit. Package the
  tag's release files without secrets, caches, or model binaries; verify the
  SHA-256 manifest and publish both assets.
- [ ] Use Ego Lite to redownload the official ZIP and manifest, verify and
  extract them into a new directory, and run both acceptance sections above.
