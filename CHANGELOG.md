# Changelog

All notable changes to DeskVerify are documented in this file.

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
