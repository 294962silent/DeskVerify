# DeskVerify v0.1.3 for Windows

Download `DeskVerify-v0.1.3-windows.zip` and `SHA256SUMS.txt` from the release
assets. Use the named Windows ZIP, not GitHub's automatic source archive.
Verify the ZIP before extracting it with PowerShell 7:

```powershell
$actual = (Get-FileHash -LiteralPath .\DeskVerify-v0.1.3-windows.zip -Algorithm SHA256).Hash
$expected = ((Get-Content -LiteralPath .\SHA256SUMS.txt -Raw).Trim() -split '\s+')[0]
if ($actual -ne $expected) { throw "DeskVerify ZIP checksum mismatch" }
```

### Windows Download Blocking

If `RemoteSigned` blocks the extracted scripts, first verify the ZIP against
the checksum from the official GitHub Release and review the source. On a
personal machine where policy permits it, unblock only the verified ZIP and
extract it into a fresh directory:

```powershell
Unblock-File -LiteralPath .\DeskVerify-v0.1.3-windows.zip
```

Do not change global execution policy or bypass organizational signing rules.

## Start

Use Windows 10/11, PowerShell 7, and Docker Desktop with Linux containers.
First startup needs internet access for images, build dependencies, and the
verified BGE ONNX download. A host JDK, Node.js, or model API key is not needed
to run the demo.

From the extracted directory, run `.\start.ps1`. Pass `-NoBrowser` to skip
opening the browser.

## Upgrade With Existing Data

1. In the old directory, run `.\stop.ps1` without `-Volumes`.
2. Privately copy the original `.env` into the new extraction. It contains
   local database, Redis, and JWT settings and may contain a provider key.
   Do not share it or replace it with `.env.example`.
3. Run `.\start.ps1` from the new directory.

The launcher checks the existing `deskverify` project's MySQL, Redis, and
backend data volumes, including stopped installations. If it finds any of
those volumes without a complete saved configuration, it stops before
modifying `.env`, downloading the model, or starting containers. Restore
the original `.env` and rerun. The check only verifies the expected settings
are present; it cannot verify that they match the old volumes. If volume
discovery fails, check Docker Desktop and retry.

If the launcher reports a conflicting MySQL or Redis environment variable,
it shows the variable name, not its value. Clear that variable from the current
PowerShell session and retry. Keep the saved value in the original `.env`.

Do not run two extractions of the fixed `deskverify` project concurrently.
`.\stop.ps1` still preserves volumes. Pass `-Volumes` only when intentionally
deleting data; it is not an upgrade or recovery step.

## Access

Both frontend and Java ports bind to `127.0.0.1` on the host. Configure
`FRONTEND_PORT` and `BACKEND_PORT` in `.env` if the defaults are already used.

- Frontend: `http://127.0.0.1:5173`
- Swagger through the frontend: `http://127.0.0.1:5173/api/java/docs`
- Swagger direct to Java: `http://127.0.0.1:8080/docs`
- Java readiness: `http://127.0.0.1:8080/actuator/health`

Paste the local DeskVerify JWT from `.deskverify/demo-token.txt` into the
frontend's Java access-token field. The simulated Acme customer can query
authorized orders and fulfillment and explicitly request a queued human
handoff without a model key. It is not a tenant administrator.

In Swagger, use **Authorize** with the same JWT, without the `Bearer` prefix.
An example read-only operation is `GET /api/v1/commerce/customers/{customerId}`
with `customer-001`. Swagger does not keep authorization across refreshes.

## Optional Native Support Model

Native provider configuration and API permissions are unchanged. See the
[native support model settings](./RELEASE_WINDOWS_V0_1_2.md#optional-native-support-model).
Do not expose model keys or use customer data unsuitable for the configured
provider.

## Scope

DeskVerify is a self-hosted MVP with simulated commerce data, not a production
service. Golden RAG uses synthetic data and does not establish production
accuracy, performance, or an SLA. Code is [MIT licensed](../LICENSE).
Separately downloaded BGE assets retain their upstream terms.

See [local development and verification](./LOCAL_DEVELOPMENT.md) and the
[v0.1.2 guide](./RELEASE_WINDOWS_V0_1_2.md) for earlier release behavior.
