# DeskVerify v0.1.2 for Windows

Download `DeskVerify-v0.1.2-windows.zip` and `SHA256SUMS.txt` from the release
assets. Use the named Windows ZIP, not GitHub's automatic source archive.
Verify the ZIP before extracting it with PowerShell 7:

```powershell
$actual = (Get-FileHash .\DeskVerify-v0.1.2-windows.zip -Algorithm SHA256).Hash
$expected = ((Get-Content .\SHA256SUMS.txt -Raw).Trim() -split '\s+')[0]
if ($actual -ne $expected) { throw "DeskVerify ZIP checksum mismatch" }
```

### Windows Download Blocking

If `RemoteSigned` blocks the extracted scripts, first verify the archive
against the checksum from the official GitHub Release and review the source.
On a personal machine where policy permits it, unblock only the verified ZIP,
then extract it again into a fresh directory:

```powershell
Unblock-File -LiteralPath .\DeskVerify-v0.1.2-windows.zip
```

Do not change the global execution policy. If your organization requires
signed scripts, follow that policy and do not bypass it.

## Start

Use Windows 10/11, PowerShell 7, and Docker Desktop with Linux containers.
The first startup needs internet access for images, build dependencies, and the
verified BGE ONNX download. A host JDK, Node.js, or model API key is not
required to run the demo.

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

Paste the local DeskVerify JWT from `.deskverify/demo-token.txt` into the
frontend's Java access-token field. The simulated Acme customer identity can
query order and fulfillment records under `customer-001` and `customer-002`
and create queued support tickets by explicitly asking for a human. These
operations work without a model key. The demo user is not a tenant administrator.

In Swagger, click **Authorize** and paste that JWT without the `Bearer`
prefix. Test the read-only `GET /api/v1/commerce/customers/{customerId}`
with `customer-001`. Swagger applies the same existing API permissions.
Its JavaScript and CSS are packaged with the backend and loaded from the same
origin. Swagger does not retain authorization across refreshes.

## Optional Native Support Model

Configure model keys in the ignored `.env` next to `start.ps1`. For DeepSeek,
select `SPRING_PROFILES_ACTIVE=deepseek,demo` and set `DEEPSEEK_API_KEY` to a
native DeepSeek API key. The default model is `deepseek-flash` and the default
base URL is `https://api.deepseek.com`. If you keep a v0.1.1 `.env` that
explicitly has `DEEPSEEK_MODEL=deepseek-chat`, change that setting to
`DEEPSEEK_MODEL=deepseek-flash`. Compatible explicit model and `DEEPSEEK_BASE_URL`
overrides are preserved. For Anthropic, select
`SPRING_PROFILES_ACTIVE=anthropic,demo` and set `ANTHROPIC_API_KEY` instead.
Run `.\start.ps1` to apply the settings.

OpenCode Go keys are not accepted for native DeepSeek support chat. Do not put
provider keys in the browser, on the command line, or in Git. Ordinary chat
falls back locally when the selected provider key is blank. Model-assisted
turns can send conversation and retrieved context to the configured provider;
use only data suitable for that provider.

## Preserve Local Data

Run `.\stop.ps1` to stop services and preserve volumes. Use `-Volumes` only
when intentionally deleting data. Keep the original `.env` when reusing
existing volumes. Releases use the same default `deskverify` Compose project
and ports; do not start two extractions concurrently with defaults.

## Scope

DeskVerify is a self-hosted MVP with simulated commerce data, not a production
service. Golden RAG uses synthetic evaluation data and zero external LLM
tokens; it does not establish production accuracy, performance, or an SLA.
Do not expose demo ports or credentials to untrusted networks. Code is
licensed under [MIT](../LICENSE); separately downloaded BGE assets retain their
upstream terms.

See [local development and verification](./LOCAL_DEVELOPMENT.md), the
[v0.1.1 guide](./RELEASE_WINDOWS_V0_1_1.md), or the
[v0.1.0 guide](./RELEASE_WINDOWS_V0_1_0.md).
