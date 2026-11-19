[CmdletBinding()]
param(
    [switch]$Volumes
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker Desktop is required."
}

$composeArgs = @(
    "compose",
    "--project-name", "deskverify",
    "--env-file", (Join-Path $root ".env"),
    "-f", (Join-Path $root "compose.yml"),
    "down"
)
if ($Volumes) {
    $composeArgs += "--volumes"
}

& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw "DeskVerify shutdown failed."
}

if ($Volumes) {
    Write-Host "DeskVerify stopped and persistent volumes were removed."
} else {
    Write-Host "DeskVerify stopped. Persistent volumes were preserved."
}
