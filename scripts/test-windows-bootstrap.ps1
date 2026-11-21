$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$temp = [IO.Path]::GetTempPath()
$testRoot = Join-Path $temp ("deskverify-windows-bootstrap-" + [guid]::NewGuid())
$failures = [System.Collections.Generic.List[string]]::new()
$critical = [ordered]@{
    MYSQL_DATABASE = "fixture-db"
    MYSQL_ROOT_PASSWORD = "fixture-root-password"
    ECHOMIND_DB_USERNAME = "fixture-user"
    ECHOMIND_DB_PASSWORD = "fixture-db-password"
    REDIS_PASSWORD = "fixture-redis-password"
    ECHOMIND_JWT_HMAC_SECRET = "fixture-jwt-secret"
    ECHOMIND_JWT_ISSUER = "fixture-issuer"
    ECHOMIND_JWT_AUDIENCE = "fixture-audience"
}
$composeDataFields = @(
    "MYSQL_DATABASE", "MYSQL_ROOT_PASSWORD", "ECHOMIND_DB_USERNAME", "ECHOMIND_DB_PASSWORD", "REDIS_PASSWORD"
)
$fixtureOverride = "fixture-private-host-setting"
$complete = ($critical.Keys | ForEach-Object { "$_=$($critical[$_])" }) -join "`n"
$complete += "`nDESKVERIFY_VERSION=0.1.2`nFRONTEND_PORT=25173`nBACKEND_PORT=28080`n"

function Assert-Test([bool]$Condition, [string]$Description) {
    if (-not $Condition) { throw $Description }
}

function docker {
    $global:LASTEXITCODE = 0
    if (($args -join " ") -ceq "info") {
        $trace.Add("info")
    } elseif (($args -join " ") -ceq "volume ls --format {{.Name}}") {
        $trace.Add("volumes")
        $global:LASTEXITCODE = $discoveryExit
        if ($discoveryExit -ne 0) { "fixture-private-docker-diagnostic" }
        else { $volumeNames }
    } elseif ($args[0] -ceq "compose" -and $args -ccontains "up") {
        $trace.Add("up")
        Assert-Test ($args[1] -ceq "--project-name" -and $args[2] -ceq "deskverify") "unexpected Compose project"
    } elseif ($args[0] -ceq "compose" -and ($args[-4..-1] -join " ") -ceq "ps --format json worker") {
        $trace.Add("worker-health")
        $calls = @($trace | Where-Object { $_ -ceq "worker-health" }).Count
        $health = if ($WorkerStartsSlow -and $calls -eq 1) { "starting" } else { "healthy" }
        @{ State = "running"; Health = $health } | ConvertTo-Json -Compress
    } else {
        throw "Unexpected Docker invocation in bootstrap test."
    }
}

function Invoke-WebRequest([switch]$UseBasicParsing, [int]$TimeoutSec, [string]$Uri) {
    $trace.Add("health")
    $checkedUrls.Add($Uri)
    return @{ StatusCode = 200 }
}

function Start-Sleep([int]$Seconds) {
    Assert-Test ($Seconds -eq 5) "unexpected readiness retry delay"
    $trace.Add("sleep")
}

function Test-Bootstrap {
    param(
        [string]$Name,
        [AllowNull()][string]$InitialEnv,
        [string[]]$VolumeNames = @(),
        [int]$DiscoveryExit = 0,
        [string]$ExpectedError = "",
        [switch]$WorkerStartsSlow
    )

    $trace = [System.Collections.Generic.List[string]]::new()
    $checkedUrls = [System.Collections.Generic.List[string]]::new()
    $case = Join-Path $testRoot $Name
    $scriptDir = Join-Path $case "scripts"
    $modelDir = Join-Path $case "EchoMindJava/scripts"
    New-Item -ItemType Directory -Path $scriptDir, $modelDir -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root "start.ps1") -Destination $case
    Copy-Item -LiteralPath (Join-Path $root "scripts/env-utils.ps1") -Destination $scriptDir
    $envFile = Join-Path $case ".env"
    if ($PSBoundParameters.ContainsKey("InitialEnv")) {
        [IO.File]::WriteAllText($envFile, $InitialEnv)
    }
    $originalHash = if (Test-Path -LiteralPath $envFile) {
        (Get-FileHash -LiteralPath $envFile -Algorithm SHA256).Hash
    }
    # Stubs exercise the launcher end to end without a daemon, browser, or download.
    [IO.File]::WriteAllText((Join-Path $scriptDir "generate-demo-jwt.ps1"), @'
param([string]$EnvFile)
$trace.Add("jwt")
"fixture-jwt"
'@)
    [IO.File]::WriteAllText((Join-Path $modelDir "download-bge-small-zh-v1.5.ps1"), @'
$trace.Add("download")
$global:LASTEXITCODE = 0
'@)

    $startupError = ""
    $output = [System.Collections.Generic.List[string]]::new()
    try { & (Join-Path $case "start.ps1") -NoBrowser -NoBuild *>&1 | ForEach-Object { $output.Add([string]$_) } }
    catch { $startupError = $_.Exception.Message }

    try {
        if ($ExpectedError) {
            Assert-Test ($startupError.Contains($ExpectedError)) "did not reject with the expected actionable error"
            Assert-Test (($trace -join ",") -ceq "info,volumes") "ran a side effect after the read-only probe"
            Assert-Test (-not (Test-Path -LiteralPath (Join-Path $case ".deskverify"))) "wrote demo state on rejection"
            $finalHash = if (Test-Path -LiteralPath $envFile) {
                (Get-FileHash -LiteralPath $envFile -Algorithm SHA256).Hash
            }
            Assert-Test ($originalHash -ceq $finalHash) "created or rewrote .env on rejection"
            $display = ($output -join "`n") + $startupError
            foreach ($value in @($critical.Values) + @("fixture-private-docker-diagnostic", $fixtureOverride)) {
                Assert-Test (-not $display.Contains($value)) "exposed fixture content on rejection"
            }
        } else {
            Assert-Test (-not $startupError) "startup failed with valid inputs"
            $expected = "info,volumes,download,jwt,up,health,health,worker-health"
            if ($WorkerStartsSlow) { $expected += ",sleep,health,health,worker-health" }
            Assert-Test (($trace -join ",") -ceq $expected) "unexpected bootstrap steps"
            Assert-Test (Test-Path -LiteralPath (Join-Path $case ".deskverify/demo-token.txt")) "did not write demo state"
            $saved = Read-DotEnv -Path $envFile
            Assert-Test ($saved["DESKVERIFY_VERSION"] -ceq "0.1.4") "did not set the current release version"
            foreach ($field in $critical.Keys) {
                Assert-Test (-not [string]::IsNullOrWhiteSpace($saved[$field])) "did not supply a required setting: $field"
                if ($InitialEnv) {
                    Assert-Test ($saved[$field] -ceq $critical[$field]) "did not preserve a saved setting: $field"
                }
            }
            $expectedPorts = if ($InitialEnv) { @(28080, 25173) } else { @(8080, 5173) }
            Assert-Test ($checkedUrls[0] -ceq "http://127.0.0.1:$($expectedPorts[0])/actuator/health" -and
                $checkedUrls[1] -ceq "http://127.0.0.1:$($expectedPorts[1])/") "did not use the saved or default ports"
        }
        Write-Output "PASS $Name"
    } catch {
        $failures.Add("${Name}: $($_.Exception.Message)")
    }
}

$savedExitCode = $global:LASTEXITCODE
$savedProcessSettings = @{}
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    . (Join-Path $root "scripts/env-utils.ps1")
    foreach ($field in $composeDataFields) {
        $savedProcessSettings[$field] = [Environment]::GetEnvironmentVariable($field, "Process")
        Remove-Item -LiteralPath "Env:$field" -ErrorAction SilentlyContinue
    }
    $restore = "restore the original .env"
    $discovery = "could not verify existing DeskVerify Docker volumes"
    Test-Bootstrap -Name "fresh" -VolumeNames @()
    Test-Bootstrap -Name "waits-for-worker-health" -WorkerStartsSlow
    Test-Bootstrap -Name "unrelated-volumes" -VolumeNames @("other_mysql-data", "deskverify_mysql-data-backup")
    Test-Bootstrap -Name "existing-mysql-without-env" -VolumeNames @("deskverify_mysql-data") -ExpectedError $restore
    Test-Bootstrap -Name "existing-backend-with-empty-env" -InitialEnv "" `
        -VolumeNames @("deskverify_backend-data") -ExpectedError $restore
    foreach ($field in $critical.Keys) {
        $partial = $complete -replace "(?m)^${field}=.*", "${field}= "
        Test-Bootstrap -Name "existing-redis-with-blank-$field" -InitialEnv $partial `
            -VolumeNames @("deskverify_redis-data") -ExpectedError $restore
    }
    Test-Bootstrap -Name "existing-with-complete-env" -InitialEnv $complete `
        -VolumeNames @("deskverify_mysql-data", "deskverify_redis-data", "deskverify_backend-data")
    Test-Bootstrap -Name "volume-discovery-exits-nonzero" -InitialEnv $complete `
        -DiscoveryExit 7 -ExpectedError $discovery
    Test-Bootstrap -Name "volume-discovery-unexpected-output" -VolumeNames @("fixture-private-docker-diagnostic", "not a volume") `
        -ExpectedError $discovery
    foreach ($field in $composeDataFields) {
        [Environment]::SetEnvironmentVariable($field, $fixtureOverride, "Process")
        Test-Bootstrap -Name "existing-conflicting-process-$field" -InitialEnv $complete `
            -VolumeNames @("deskverify_mysql-data") -ExpectedError "overrides the saved .env setting: $field"
        Remove-Item -LiteralPath "Env:$field" -ErrorAction SilentlyContinue
    }
    [Environment]::SetEnvironmentVariable("REDIS_PASSWORD", "", "Process")
    if ($null -ne [Environment]::GetEnvironmentVariable("REDIS_PASSWORD", "Process")) {
        Test-Bootstrap -Name "existing-with-empty-process-setting" -InitialEnv $complete `
            -VolumeNames @("deskverify_mysql-data") -ExpectedError "overrides the saved .env setting: REDIS_PASSWORD"
    }
    foreach ($field in $composeDataFields) {
        [Environment]::SetEnvironmentVariable($field, $critical[$field], "Process")
    }
    Test-Bootstrap -Name "existing-with-matching-process-settings" -InitialEnv $complete `
        -VolumeNames @("deskverify_mysql-data")

    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
        exit 1
    }
    Write-Output "DeskVerify Windows bootstrap checks passed (mocked Docker)."
} finally {
    foreach ($field in $savedProcessSettings.Keys) {
        if ($null -eq $savedProcessSettings[$field]) {
            Remove-Item -LiteralPath "Env:$field" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($field, $savedProcessSettings[$field], "Process")
        }
    }
    $global:LASTEXITCODE = $savedExitCode
    $resolved = (Resolve-Path -LiteralPath $testRoot).ProviderPath
    $parent = (Resolve-Path -LiteralPath $temp).ProviderPath.TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($resolved) -ceq $parent -and
            [IO.Path]::GetFileName($resolved) -match '^deskverify-windows-bootstrap-[0-9a-f-]{36}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
