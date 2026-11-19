# DeskVerify v0.1.1 for Windows

Download `DeskVerify-v0.1.1-windows.zip` and `SHA256SUMS.txt` from the release
assets. The automatically generated GitHub source archive is not the curated
Windows package.

In PowerShell 7, verify the hash before extracting the ZIP:

```powershell
$actual = (Get-FileHash .\DeskVerify-v0.1.1-windows.zip -Algorithm SHA256).Hash
$expected = ((Get-Content .\SHA256SUMS.txt -Raw).Trim() -split '\s+')[0]
if ($actual -ne $expected) { throw "DeskVerify ZIP checksum mismatch" }
```

## Start

Use Windows 10/11, PowerShell 7, and a running Docker Desktop with Linux
containers. First startup needs internet access for images, build dependencies,
and the verified BGE ONNX model download. A host JDK, Node.js, or an LLM provider
key is not required.

From the extracted directory:

```powershell
.\start.ps1
```

Use `.\start.ps1 -NoBrowser` to skip opening the browser.

## Access

| Service | Default URL |
| --- | --- |
| Frontend | `http://localhost:5173` |
| Swagger via the frontend | `http://localhost:5173/api/java/docs` |
| Swagger direct to Java | `http://localhost:8080/docs` |
| Java readiness | `http://localhost:8080/actuator/health` |

Paste the local JWT from `.deskverify/demo-token.txt` into the frontend's Java
access-token field. The Acme demo identity can use `customer-001` and
`customer-002`. Keep the token and `.env` private.

With no LLM provider key, try:

- `请查一下 customer-001 的订单，列出订单号和状态。` to query actual local
  authorized order records. Logistics answers can show recorded fulfillment
  times but not carrier tracking or estimated delivery.
- `我要转人工，找人工客服处理工单。` to create a queued ticket. Look up the
  returned ticket ID in the workbench. A retry for the same customer and
  conversation reuses the ticket within the 24-hour idempotency window.

Other chat requests may still use a local fallback response. The demo user is
not a tenant administrator: knowledge management and Golden RAG evaluation
require a separately authorized tenant administrator. Saving or removing the
session token updates the evaluation panel without reloading the browser.

## Preserve Local Data

Run `.\stop.ps1` to stop services while preserving volumes. Use `-Volumes` only
when intentionally deleting local data. Upgrading with existing volumes
requires the original `.env` credentials. The launch scripts use the same
`deskverify` Docker Compose project across releases; do not start two
extractions concurrently under the default configuration.

## Scope

This is a self-hosted demo, not a production service. Do not expose the demo
ports or credentials to untrusted networks. Golden RAG uses a synthetic
dataset with zero external LLM tokens and is not a claim about accuracy or
latency in production. Source code is licensed under [MIT](../LICENSE);
separately downloaded BGE assets retain their upstream license terms.

See [local development and verification](./LOCAL_DEVELOPMENT.md) for model,
test, and configuration details, or the [original Windows release guide](./RELEASE_WINDOWS_V0_1_0.md)
for v0.1.0.
