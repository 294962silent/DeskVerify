[CmdletBinding()]
param(
    [ValidateSet("anthropic", "deepseek")]
    [string]$Provider,
    [string]$AnthropicApiKey,
    [string]$DeepSeekApiKey,
    [switch]$NoBuild,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$composeProject = "deskverify"
. (Join-Path $root "scripts\env-utils.ps1")

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "DeskVerify requires PowerShell 7 or newer."
}
if (-not $IsWindows) {
    throw "This release bootstrap currently supports Windows with Docker Desktop."
}
function New-RandomHex([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ([Convert]::ToHexString($bytes)).ToLowerInvariant()
}

function New-JwtSecret {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Set-DefaultValue([System.Collections.IDictionary]$Values, [string]$Name, [string]$Value) {
    if (-not $Values.Contains($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        $Values[$Name] = $Value
    }
}

$envPath = Join-Path $root ".env"
$values = Read-DotEnv -Path $envPath

if ($DeepSeekApiKey) {
    $Provider = "deepseek"
    $values["DEEPSEEK_API_KEY"] = $DeepSeekApiKey
}
if ($AnthropicApiKey) {
    if ($Provider -and $Provider -ne "anthropic") {
        throw "Specify only one provider key."
    }
    $Provider = "anthropic"
    $values["ANTHROPIC_API_KEY"] = $AnthropicApiKey
}
$selectedProfile = [string]$values["SPRING_PROFILES_ACTIVE"]
if ($Provider) {
    $selectedProfile = "$Provider,demo"
} elseif ([string]::IsNullOrWhiteSpace($selectedProfile)) {
    $selectedProfile = "anthropic,demo"
}
$selectedProfiles = $selectedProfile.Split(',', [StringSplitOptions]::TrimEntries)
if ($DeepSeekApiKey -match '^\s*oc_sk_' -or
        ($selectedProfiles -contains "deepseek" -and
            [string]$values["DEEPSEEK_API_KEY"] -match '^\s*oc_sk_')) {
    throw "DEEPSEEK_API_KEY is an OpenCode Go key. Configure a native DeepSeek API key in .env, or leave it empty for local fallback."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker Desktop is required. Install it and reopen PowerShell."
}
$null = & docker info 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not running or is unavailable."
}

# Docker lists detached volumes too; compare the exact names Compose can reuse.
$discoveryError = "Startup could not verify existing DeskVerify Docker volumes. Check that Docker Desktop can list volumes, then retry."
try {
    $volumeNames = @(& docker volume ls --format '{{.Name}}' 2>&1)
    $volumeExitCode = $LASTEXITCODE
} catch {
    throw $discoveryError
}
if ($volumeExitCode -ne 0 -or
        ($volumeNames | Where-Object { $_ -isnot [string] -or $_ -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$' })) {
    throw $discoveryError
}
$hasExistingData = $volumeNames -ccontains "${composeProject}_mysql-data" -or
    $volumeNames -ccontains "${composeProject}_redis-data" -or
    $volumeNames -ccontains "${composeProject}_backend-data"
if ($hasExistingData) {
    $composeDataSettings = @(
        "MYSQL_DATABASE", "MYSQL_ROOT_PASSWORD", "ECHOMIND_DB_USERNAME", "ECHOMIND_DB_PASSWORD", "REDIS_PASSWORD"
    )
    foreach ($required in $composeDataSettings + @(
        "ECHOMIND_JWT_HMAC_SECRET", "ECHOMIND_JWT_ISSUER", "ECHOMIND_JWT_AUDIENCE")) {
        if ([string]::IsNullOrWhiteSpace([string]$values[$required])) {
            throw "Existing DeskVerify Docker volumes were found, but .env is missing or incomplete. Please restore the original .env next to start.ps1 and retry. Existing volumes were not changed."
        }
    }
    foreach ($setting in $composeDataSettings) {
        $processValue = [Environment]::GetEnvironmentVariable($setting, "Process")
        if ($null -ne $processValue -and $processValue -cne [string]$values[$setting]) {
            throw "Startup stopped because a process environment variable overrides the saved .env setting: $setting. Clear that variable in the current shell and retry."
        }
    }
} else {
    Set-DefaultValue $values "MYSQL_DATABASE" "deskverify"
    Set-DefaultValue $values "MYSQL_ROOT_PASSWORD" (New-RandomHex 24)
    Set-DefaultValue $values "ECHOMIND_DB_USERNAME" "deskverify"
    Set-DefaultValue $values "ECHOMIND_DB_PASSWORD" (New-RandomHex 24)
    Set-DefaultValue $values "REDIS_PASSWORD" (New-RandomHex 24)
    Set-DefaultValue $values "ECHOMIND_JWT_HMAC_SECRET" (New-JwtSecret)
    Set-DefaultValue $values "ECHOMIND_JWT_ISSUER" "echomind-local"
    Set-DefaultValue $values "ECHOMIND_JWT_AUDIENCE" "echomind-api"
}

$values["DESKVERIFY_VERSION"] = "0.1.4"
Set-DefaultValue $values "FRONTEND_PORT" "5173"
Set-DefaultValue $values "BACKEND_PORT" "8080"
Set-DefaultValue $values "LLM_FALLBACK_ENABLED" "true"
Set-DefaultValue $values "ANTHROPIC_API_KEY" ""
Set-DefaultValue $values "ANTHROPIC_MODEL" "claude-3-5-sonnet-20241022"
Set-DefaultValue $values "DEEPSEEK_API_KEY" ""
Set-DefaultValue $values "DEEPSEEK_MODEL" "deepseek-flash"
$values["SPRING_PROFILES_ACTIVE"] = $selectedProfile

Write-DotEnv -Path $envPath -Values $values

if ($selectedProfiles -contains "deepseek" -and
        [string]::IsNullOrWhiteSpace([string]$values["DEEPSEEK_API_KEY"])) {
    Write-Warning "DEEPSEEK_API_KEY is empty; DeskVerify will use its local fallback responses."
}
if ($selectedProfiles -contains "anthropic" -and
        [string]::IsNullOrWhiteSpace([string]$values["ANTHROPIC_API_KEY"])) {
    Write-Warning "ANTHROPIC_API_KEY is empty; DeskVerify will use its local fallback responses."
}

$modelPath = Join-Path $root "EchoMindJava\models\bge-small-zh-v1.5\model.onnx"
$tokenizerPath = Join-Path $root "EchoMindJava\models\bge-small-zh-v1.5\tokenizer.json"
if (-not (Test-Path -LiteralPath $modelPath) -or -not (Test-Path -LiteralPath $tokenizerPath)) {
    Write-Host "Downloading and verifying the BGE ONNX model..."
    & (Join-Path $root "EchoMindJava\scripts\download-bge-small-zh-v1.5.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "BGE model download failed."
    }
}

$stateDir = Join-Path $root ".deskverify"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$jwt = (& (Join-Path $root "scripts\generate-demo-jwt.ps1") -EnvFile $envPath).Trim()
if (-not $jwt) {
    throw "Demo JWT generation failed."
}
$jwtPath = Join-Path $stateDir "demo-token.txt"
[IO.File]::WriteAllText($jwtPath, $jwt, [Text.UTF8Encoding]::new($false))

$composePath = Join-Path $root "compose.yml"
$composeArgs = @(
    "compose",
    "--project-name", $composeProject,
    "--env-file", $envPath,
    "-f", $composePath,
    "up",
    "-d"
)
if (-not $NoBuild) {
    $composeArgs += "--build"
}

Write-Host "Starting DeskVerify..."
& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose startup failed."
}

$backendPort = [int]$values["BACKEND_PORT"]
$frontendPort = [int]$values["FRONTEND_PORT"]
$deadline = (Get-Date).AddMinutes(5)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        $backend = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$backendPort/actuator/health"
        $frontend = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$frontendPort/"
        if ($backend.StatusCode -eq 200 -and $frontend.StatusCode -eq 200) {
            $workerStatus = & docker compose --project-name $composeProject --env-file $envPath -f $composePath ps --format json worker 2>$null
            if ($LASTEXITCODE -eq 0 -and $workerStatus) {
                $worker = ($workerStatus -join "`n") | ConvertFrom-Json
                if ($worker.State -eq "running" -and $worker.Health -eq "healthy") {
                    $ready = $true
                    break
                }
            }
        }
    } catch {
        # Compose health and HTTP endpoints may not be available yet.
    }
    Start-Sleep -Seconds 5
}

if (-not $ready) {
    & docker compose --project-name $composeProject --env-file $envPath -f $composePath ps
    throw "DeskVerify (including the Outbox worker) did not become healthy within five minutes. Run: docker compose --project-name $composeProject -f compose.yml logs"
}

Write-Host ""
Write-Host "DeskVerify is ready."
Write-Host "Frontend:     http://127.0.0.1:$frontendPort"
Write-Host "Swagger:      http://127.0.0.1:$backendPort/docs"
Write-Host "Health:       http://127.0.0.1:$backendPort/actuator/health"
Write-Host "Demo JWT:     $jwtPath"
Write-Host "Demo tenant:  acme"
Write-Host "Demo user:    demo-acme-user"
Write-Host ""
Write-Host "Paste the Demo JWT into the frontend's Java access-token field."

if (-not $NoBrowser) {
    Start-Process "http://127.0.0.1:$frontendPort"
}
