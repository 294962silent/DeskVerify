param(
    [switch]$SkipComposeConfig
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[string]]::new()

$requiredFiles = @(
    "compose.yml",
    ".env.example",
    "start.ps1",
    "stop.ps1",
    "scripts/env-utils.ps1",
    "scripts/generate-demo-jwt.ps1",
    "scripts/test-support-chat-provider.ps1",
    "CHANGELOG.md",
    "docs/RELEASE_WINDOWS_V0_1_0.md",
    "docs/RELEASE_WINDOWS_V0_1_2.md",
    "README.md",
    "LICENSE"
)

foreach ($relativePath in $requiredFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $failures.Add("Missing release file: $relativePath")
    }
}

$composePath = Join-Path $root "compose.yml"
if (Test-Path -LiteralPath $composePath -PathType Leaf) {
    $compose = Get-Content -LiteralPath $composePath -Raw
    foreach ($service in @("mysql", "redis", "backend", "frontend")) {
        if ($compose -notmatch "(?m)^  ${service}:\s*$") {
            $failures.Add("Compose service is missing: $service")
        }
    }
    foreach ($forbidden in @("echomind-python", "../EchoMind", "chromadb")) {
        if ($compose.Contains($forbidden)) {
            $failures.Add("Forbidden Python/legacy compose entry: $forbidden")
        }
    }
}

$activeFrontendFiles = @(
    "EchoMindFrontend/src/App.vue",
    "EchoMindFrontend/src/lib/auth.js",
    "EchoMindFrontend/src/lib/backends.js",
    "EchoMindFrontend/public/runtime-config.js",
    "EchoMindFrontend/vite.config.js",
    "EchoMindFrontend/docker/entrypoint.sh",
    "EchoMindFrontend/docker/nginx.conf"
)

foreach ($relativePath in $activeFrontendFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        continue
    }
    $content = Get-Content -LiteralPath $path -Raw
    if ($content -match "(?i)python|/api/python|pythonApiUrl") {
        $failures.Add("Python residue remains in $relativePath")
    }
}

$startPath = Join-Path $root "start.ps1"
if (Test-Path -LiteralPath $startPath -PathType Leaf) {
    $start = Get-Content -LiteralPath $startPath -Raw
    foreach ($requiredToken in @(
        "download-bge-small-zh-v1.5.ps1",
        "generate-demo-jwt.ps1",
        "docker",
        "compose"
    )) {
        if (-not $start.Contains($requiredToken)) {
            $failures.Add("start.ps1 is missing bootstrap token: $requiredToken")
        }
    }
}

if (-not $SkipComposeConfig -and
        (Test-Path -LiteralPath $composePath -PathType Leaf) -and
        (Get-Command docker -ErrorAction SilentlyContinue)) {
    $null = & docker compose -f $composePath config --quiet 2>&1
    if ($LASTEXITCODE -ne 0) {
        $failures.Add("docker compose config failed")
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "DeskVerify Windows release layout is valid."
