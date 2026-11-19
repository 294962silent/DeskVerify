# DeskVerify v0.1.0 Windows Release

DeskVerify `v0.1.0` is the first Windows-first self-hosted release.

## Prerequisites

- Windows 10 or Windows 11
- PowerShell 7
- Docker Desktop using Linux containers
- Internet access on first startup to pull images and build dependencies and
  download the BGE ONNX model

No local Java, MySQL, Redis, or Node.js installation is required.

## Start

From the extracted release directory:

```powershell
.\start.ps1
```

Optional real-model configuration:

```powershell
.\start.ps1 -Provider deepseek -DeepSeekApiKey "your_key"
```

or:

```powershell
.\start.ps1 -Provider anthropic -AnthropicApiKey "your_key"
```

Without a provider key, DeskVerify uses its local fallback response path.
Provider keys are stored in the local `.env`; keep that file and the demo JWT private.
Use `-NoBrowser` to start without opening a browser tab.

## Access

```text
Frontend: http://localhost:5173
Swagger:  http://localhost:8080/docs
Health:   http://localhost:8080/actuator/health
```

The script writes a one-day Acme demo JWT to:

```text
.deskverify/demo-token.txt
```

Paste it into the frontend's Java access-token field.

Demo identity:

```text
tenant_id = acme
sub       = demo-acme-user
customer  = customer-001 / customer-002
```

The demo identity can read Acme Commerce data. It is not a tenant admin and
cannot submit Golden RAG evaluations or manage knowledge documents.

To check the demo customer API from the release root without displaying the JWT:

```powershell
$demoHeaders = @{ Authorization = "Bearer $((Get-Content .deskverify/demo-token.txt -Raw).Trim())" }
$customerApi = "api/v1/commerce/customers/customer-001"
(Invoke-RestMethod "http://localhost:8080/$customerApi" -Headers $demoHeaders).account.customer_id
(Invoke-RestMethod "http://localhost:5173/api/java/$customerApi" -Headers $demoHeaders).account.customer_id
```

Both commands should print `customer-001`.

## Persistent State

The stack uses named volumes for MySQL, Redis, and backend runtime files.

Stop while preserving data:

```powershell
.\stop.ps1
```

Stop and delete persistent data:

```powershell
.\stop.ps1 -Volumes
```

## Troubleshooting

Show services:

```powershell
docker compose --project-name deskverify -f compose.yml ps
```

Show logs:

```powershell
docker compose --project-name deskverify -f compose.yml logs
```

Keep `.env` together with the named volumes. Deleting it can regenerate
credentials that no longer match an existing MySQL volume and invalidate
previous demo JWTs. To recover a running installation, restore its original
`.env` rather than regenerating credentials. Use `-Volumes` only when you
intend to discard the persisted data.

## v0.1.0 Boundaries

- Docker Desktop is required.
- The release is a self-hosted MVP, not a hosted production service.
- Do not expose the demo ports or credentials to untrusted networks.
- Golden evaluation datasets are synthetic and not production representative.
- The first model download is approximately 95 MB.
