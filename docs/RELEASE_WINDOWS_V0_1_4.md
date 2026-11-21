# DeskVerify v0.1.4 for Windows

Download `DeskVerify-v0.1.4-windows.zip` and `SHA256SUMS.txt` from the same
GitHub Release. Open PowerShell 7 in the download directory and verify the ZIP
before extracting it:

```powershell
$expected = ((Get-Content -LiteralPath .\SHA256SUMS.txt -Raw).Trim() -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath .\DeskVerify-v0.1.4-windows.zip -Algorithm SHA256).Hash
if ($actual -ne $expected) { throw 'DeskVerify ZIP checksum mismatch.' }
```

Follow the [Windows startup and safe upgrade guide](RELEASE_WINDOWS_V0_1_3.md)
with this release's ZIP and directory names. Keep the existing `.env` when
upgrading, back it up locally, and do not delete the existing volumes. The
launcher downloads and checks the BGE ONNX files if they are not already cached.
Existing loopback bindings and `stop.ps1` volume preservation still apply.

The Windows stack includes a separate Outbox worker. It uses the MySQL lease to
take over abandoned work and writes a tenant-scoped Inbox receipt for each
processed event. Repeated delivery is possible; the durable local receipt is
deduplicated. This is not an external notification or broker integration.

DeskVerify is an MVP with simulated commerce data and a synthetic Golden
evaluation dataset. It is not a production deployment or a production quality
benchmark.
