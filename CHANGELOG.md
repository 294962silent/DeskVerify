# Changelog

All notable changes to DeskVerify are documented in this file.

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
