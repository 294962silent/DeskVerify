# DeskVerify Windows v0.1.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Windows-first DeskVerify `v0.1.0` release that starts the complete Java + Vue stack with one PowerShell command.

**Architecture:** A root `compose.yml` runs MySQL, Redis, the Java backend, and the Nginx-served Vue frontend. PowerShell bootstrap scripts generate local secrets, download the BGE model, create a demo JWT, launch the stack, and validate health.

**Tech Stack:** Docker Compose, PowerShell 7, MySQL 8.4, Redis 7, Spring Boot 3.5, Java 21, BGE ONNX, Vue 3, Nginx, GitHub Release.

## Global Constraints

- Docker Desktop is the only required host dependency.
- The active runtime is Java-only; no Python service or proxy remains.
- Local secrets and demo tokens must never be committed.
- The model is downloaded by the start script and is not part of Git or the release ZIP.
- Health checks must reflect actual backend/frontend readiness.
- `stop.ps1` preserves volumes unless `-Volumes` is explicitly supplied.
- Release tag: `v0.1.0`.

---

### Task 1: Add Failing Release-Layout Tests

**Files:**
- Create: `scripts/test-release-layout.ps1`

- [ ] **Step 1: Write the test script**

Check for root `compose.yml`, `start.ps1`, `stop.ps1`, and the JWT helper. Parse
Compose with `docker compose config --quiet` when Docker is available. Fail if
active delivery files contain Python services, `../EchoMind`, Chroma, or the
frontend backend selector.

- [ ] **Step 2: Run the test and verify RED**

```powershell
.\scripts\test-release-layout.ps1
```

Expected: failure because the release files do not exist yet.

- [ ] **Step 3: Commit**

```powershell
git add -- scripts/test-release-layout.ps1
git commit -m "test: define Windows release layout"
```

### Task 2: Remove Python Delivery Path

**Files:**
- Modify: `EchoMindFrontend/src/lib/auth.js`
- Modify: `EchoMindFrontend/src/lib/auth.test.js`
- Modify: `EchoMindFrontend/src/lib/backends.js`
- Modify: `EchoMindFrontend/src/App.vue`
- Modify: `EchoMindFrontend/public/runtime-config.js`
- Modify: `EchoMindFrontend/vite.config.js`
- Modify: `EchoMindFrontend/docker/entrypoint.sh`
- Modify: `EchoMindFrontend/docker/nginx.conf`
- Delete: `EchoMindFrontend/docker-compose.yml`
- Delete: `EchoMindFrontend/docker/nginx-gateway.conf`
- Modify: `EchoMindFrontend/README.md`
- Modify: `README.md`

- [ ] **Step 1: Add or update failing frontend tests**

Require Java as the only backend, require a token for every request, and remove
the Python transport case.

- [ ] **Step 2: Run tests and verify RED**

```powershell
cd EchoMindFrontend
npm test -- --run
```

- [ ] **Step 3: Implement Java-only transport and Nginx proxy**

Remove backend tabs, Python runtime config, Python proxy, and stale Compose.

- [ ] **Step 4: Run tests and build**

```powershell
npm test -- --run
npm run build
```

Expected: all tests pass and build succeeds.

### Task 3: Add Root Compose And Windows Bootstrap

**Files:**
- Create: `compose.yml`
- Create: `.env.example`
- Create: `start.ps1`
- Create: `stop.ps1`
- Create: `scripts/generate-demo-jwt.ps1`
- Modify: `EchoMindJava/Dockerfile`
- Modify: `.gitignore`
- Delete: `EchoMindJava/docker-compose.yml`

- [ ] **Step 1: Run release-layout test and verify RED**

Expected: missing Compose/bootstrap files.

- [ ] **Step 2: Implement root stack**

Add MySQL, Redis, backend, and frontend services with health checks and named
volumes. Backend uses generated `.env`, internal service hostnames, and the demo
profile.

- [ ] **Step 3: Implement scripts**

`start.ps1` generates secrets, downloads the model, creates the demo JWT, starts
Compose, waits for health, and prints the token. `stop.ps1` stops the stack.

- [ ] **Step 4: Validate layout and Compose**

```powershell
.\scripts\test-release-layout.ps1
docker compose -f compose.yml config --quiet
```

### Task 4: Document And Validate One-Command Start

**Files:**
- Modify: `README.md`
- Create: `CHANGELOG.md`
- Create: `docs/RELEASE_WINDOWS_V0_1_0.md`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Document Windows prerequisites and commands**

Document `.\start.ps1`, `.\stop.ps1`, URLs, JWT flow, optional API keys, and
volume behavior.

- [ ] **Step 2: Add CI release-layout validation**

Run `scripts/test-release-layout.ps1` in CI.

- [ ] **Step 3: Run full regression**

```powershell
.\scripts\test-release-layout.ps1
cd EchoMindFrontend
npm test -- --run
npm run build
cd ..\EchoMindJava
.\mvnw.cmd test
```

- [ ] **Step 4: Start the real stack**

```powershell
cd E:\EchoMind\echomind-saas-agent
.\start.ps1 -NoBrowser
```

Verify:

- `http://localhost:8080/health`;
- `http://localhost:5173`;
- the demo JWT can request `/api/v1/commerce/customers/customer-001`;
- `.\stop.ps1` shuts down while preserving volumes.

### Task 5: Publish v0.1.0

**Files:**
- Release artifacts: `DeskVerify-v0.1.0-windows.zip`, `SHA256SUMS.txt`

- [ ] **Step 1: Commit and push release changes**

- [ ] **Step 2: Wait for main CI success**

- [ ] **Step 3: Create tag `v0.1.0`**

- [ ] **Step 4: Build Windows source ZIP from the tag**

- [ ] **Step 5: Publish GitHub Release**

Attach ZIP and checksum, with Windows-first startup instructions.

- [ ] **Step 6: Verify public release assets and links**
