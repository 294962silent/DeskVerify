# Changelog

All notable changes to DeskVerify are documented in this file.

## v0.1.4

### Fixed

- Customer chat can retrieve an authorized, active knowledge version and
  quote its source without a model provider key.
- Chat can initialize BGE ONNX from the packaged Java image even when its
  first embedding runs on a background thread. The knowledge tool trace
  distinguishes actual vector evidence from lexical fallback.

### Added

- An independent Windows Compose worker processes ticket Outbox events.
  Expired leases can be reclaimed, and repeated delivery is deduplicated
  by a durable MySQL Inbox receipt. There is no external notification.

## v0.1.3 - 2026-11-21

### Fixed

- Bind the Windows demo's published frontend and backend ports to `127.0.0.1`,
  while keeping both host ports configurable.
- Stop startup before modifying `.env`, downloading models, or starting
  services if existing DeskVerify data volumes cannot be inspected, or if they
  exist and the original `.env` is missing critical settings. Preserve the
  existing volume names and stop behavior.

### Added

- Windows regression coverage for fresh setup, safe upgrade refusal, preserved
  settings, and Docker volume discovery failures.

## v0.1.2 - 2026-11-20

### Fixed

- Native DeepSeek support chat defaults to `deepseek-flash`. Existing
  explicit model and endpoint settings are preserved. Blank provider keys
  boot in local fallback mode, and the Windows launcher rejects OpenCode Go
  keys in the native DeepSeek slot without displaying them.
- Swagger **Authorize** accepts a DeskVerify JWT on both direct and proxied
  URLs. Swagger's JavaScript and CSS are packaged and served from the same
  origin. It does not store the JWT across refreshes or change API permissions.

### Added

- Windows CI regression for the provider configuration guard, using only
  synthetic values and no external provider calls.

## v0.1.1 - 2026-11-19

### Fixed

- With no LLM provider key, authenticated chat reads order and fulfillment
  records for the selected authorized customer and cites the actual tool call
  in the request trace. It does not invent carrier or shipment tracking data.
- Explicit requests for a human create a queued support ticket. The browser
  reuses the Java conversation ID, and same-conversation retries reuse the
  ticket within the normal idempotency window.
- Swagger works at the direct Java endpoint and through `/api/java` and uses
  the correct origin and prefix for Try It Out.
- Saving, replacing, or removing the session token refreshes evaluation
  status and prevents older responses from restoring stale job or report data.

## v0.1.0 - 2026-11-19

### Added

- Windows-first one-command Docker startup with `start.ps1`.
- MySQL 8.4, Redis 7, Java backend, and Vue frontend in one Compose stack.
- Automatic local `.env`, JWT secret, and demo JWT generation.
- BGE ONNX model download and SHA-256 verification during bootstrap.
- Startup readiness checks for the Java backend and frontend.
- Windows release guide, Compose layout validation, and MIT License.

### Changed

- Project and repository branding renamed from EchoMind to DeskVerify.
- Frontend runtime simplified to the Java backend only.
- Redis and MySQL data use persistent named volumes.

### Removed

- Python backend and proxy routes from the active delivery path.
- Legacy frontend backend selector.
- ChromaDB from the release stack.
