# DeskVerify Windows One-Command Release Design

Date: 2026-11-19

Release: `v0.1.0`

## Goal

Provide a Windows-first DeskVerify release that starts the complete application with:

```powershell
.\start.ps1
```

Docker Desktop is the only required host dependency.

## User Experience

The release archive contains source code, Compose configuration, PowerShell
scripts, documentation, and checksums. It intentionally excludes generated
models, dependencies, build output, local secrets, and runtime data.

`start.ps1` performs these steps:

1. Verify Docker Desktop and Docker Compose are available.
2. Generate `.env` with random MySQL, Redis, and JWT secrets when missing.
3. Download and SHA-256-verify the local BGE ONNX model when missing.
4. Generate a one-day demo JWT for `demo-acme-user` in tenant `acme`.
5. Build and start MySQL, Redis, backend, and frontend containers.
6. Wait for backend and frontend health checks.
7. Save the demo JWT under ignored `.deskverify/`.
8. Print URLs and the JWT path, then optionally open the browser.

The first release is Java-only. Python backend services, Python proxy routes,
and the frontend backend selector are removed from the active delivery path.

## Runtime Architecture

### MySQL

- Image: `mysql:8.4`
- Database: `deskverify`
- Data: named volume
- Health: `mysqladmin ping`
- Flyway applies V1-V11 and the `demo` profile's V100 data automatically.

### Redis

- Image: `redis:7-alpine`
- Password comes from generated `.env`.
- Data: named volume.
- Health: authenticated `redis-cli ping`.

### Backend

- Build context: `EchoMindJava`
- Image contains the downloaded BGE model.
- Runs with Spring profile `${SPRING_PROFILES_ACTIVE:-anthropic,demo}`.
- MySQL and Redis hostnames are internal Compose service names.
- Exposes port `8080` for API and Swagger access.
- Health: `/health`.

### Frontend

- Build context: `EchoMindFrontend`
- Nginx serves the Vue production build.
- Runtime config contains only `javaApiUrl: /api/java`.
- `/api/java/` proxies to the backend service.
- Exposes port `5173`.
- Health: HTTP request to `/`.

ChromaDB and the Python backend are not part of the release stack.

## Bootstrap Secrets

Generated secrets are stored only in ignored `.env`:

- `MYSQL_ROOT_PASSWORD`
- `ECHOMIND_DB_PASSWORD`
- `REDIS_PASSWORD`
- `ECHOMIND_JWT_HMAC_SECRET`

The JWT secret is Base64-encoded random bytes and must represent at least 32
decoded bytes.

Provider keys are optional. Without a real provider key, the application runs
with its local fallback enabled. Users can pass either a DeepSeek or Anthropic
key through script parameters.

## Scripts

### `start.ps1`

Parameters:

- `-Provider anthropic|deepseek`
- `-AnthropicApiKey <string>`
- `-DeepSeekApiKey <string>`
- `-NoBuild`
- `-NoBrowser`

### `stop.ps1`

Stops the stack. `-Volumes` additionally removes persisted data and must be an
explicit opt-in.

### `scripts/generate-demo-jwt.ps1`

Reads `.env` and creates an HS256 JWT with:

- `iss=echomind-local`
- `aud=echomind-api`
- `sub=demo-acme-user`
- `tenant_id=acme`
- `iat`, `exp`, `jti`

The script prints the token without writing secrets into Git.

### `scripts/test-release-layout.ps1`

Validates:

- required release files exist;
- Compose contains exactly the expected MySQL, Redis, backend, and frontend services;
- no active Python service, proxy, or frontend selector remains;
- Compose configuration parses when Docker is available.

## Error Handling

- Missing Docker Desktop: stop with installation guidance.
- Model download/checksum failure: stop before Compose build.
- Compose build/start failure: print `docker compose ps` and a clear failure.
- Health timeout: preserve containers for diagnosis and print logs command.
- Existing `.env`: preserve user edits; do not regenerate secrets automatically.
- Existing data: `stop.ps1` preserves volumes unless `-Volumes` is explicit.

## Release Artifact

Tag and release `v0.1.0` includes:

- GitHub source ZIP;
- `DeskVerify-v0.1.0-windows.zip`;
- `SHA256SUMS.txt`;
- release notes with prerequisites, startup command, access URLs, token flow,
  optional provider configuration, and known boundaries.

## Verification

Release is blocked unless:

- release layout tests pass;
- backend tests pass;
- frontend tests and production build pass;
- `docker compose config` passes;
- `.\start.ps1 -NoBrowser` starts a real local stack;
- backend `/health` and frontend `/` return successfully;
- the demo JWT can read Acme Commerce data;
- `stop.ps1` shuts down the stack without removing volumes.

## Known Boundaries

- Docker Desktop is required.
- First startup downloads a model of roughly 95 MB and builds images.
- The `.env` and demo token are local development credentials, not production
  secrets.
- The release is a self-hosted MVP, not a hosted production service.

