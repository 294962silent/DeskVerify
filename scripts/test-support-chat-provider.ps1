$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$expected = "deepseek-flash"
$fixture = "oc_" + "sk_fixture-only-123456789"
$message = "DEEPSEEK_API_KEY is an OpenCode Go key"

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Description) {
    if ($Actual -cne $Expected) {
        throw "Assertion failed: $Description"
    }
}

# No Docker daemon is used. A successful early guard must not reach this stand-in.
function docker {
    throw "Unexpected Docker invocation in support provider regression."
}

$temp = [IO.Path]::GetTempPath()
$testRoot = Join-Path $temp ("deskverify-support-provider-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path (Join-Path $testRoot "scripts") -Force | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $root "start.ps1") -Destination $testRoot
    Copy-Item -LiteralPath (Join-Path $root "scripts\env-utils.ps1") -Destination (Join-Path $testRoot "scripts")
    $startup = Join-Path $testRoot "start.ps1"
    $testEnv = Join-Path $testRoot ".env"
    $savedProfile = $env:SPRING_PROFILES_ACTIVE
    try {
        Remove-Item -LiteralPath Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
        $errorText = ""
        try { $null = & $startup -Provider deepseek -DeepSeekApiKey $fixture -NoBrowser *>&1 }
        catch { $errorText = $_.Exception.Message }
        Assert-Equal $errorText.StartsWith($message) $true "Go key passed as DeepSeek argument is rejected before Docker"
        Assert-Equal $errorText.Contains($fixture) $false "launcher error does not contain the key"
        Assert-Equal (Test-Path -LiteralPath $testEnv) $false "invalid argument does not create .env"

        $initial = "SPRING_PROFILES_ACTIVE=demo,deepseek`nDEEPSEEK_API_KEY=$fixture`nDEEPSEEK_MODEL=custom-model`n"
        [IO.File]::WriteAllText($testEnv, $initial)
        $errorText = ""
        try { $null = & $startup -NoBrowser *>&1 }
        catch { $errorText = $_.Exception.Message }
        Assert-Equal $errorText.StartsWith($message) $true "Go key in saved DeepSeek profile is rejected before Docker"
        Assert-Equal $errorText.Contains($fixture) $false "saved key is never echoed"
        Assert-Equal ([IO.File]::ReadAllText($testEnv)) $initial "invalid configuration does not rewrite .env"

        foreach ($valid in @("", "fixture-native")) {
            [IO.File]::WriteAllText($testEnv, "SPRING_PROFILES_ACTIVE=deepseek,demo`nDEEPSEEK_API_KEY=$valid`n")
            $errorText = ""
            try { $null = & $startup -NoBrowser *>&1 }
            catch { $errorText = $_.Exception.Message }
            Assert-Equal $errorText "Unexpected Docker invocation in support provider regression." `
                "empty and native-style credentials pass the provider guard"
        }
    } finally {
        if ($null -eq $savedProfile) {
            Remove-Item -LiteralPath Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
        } else {
            $env:SPRING_PROFILES_ACTIVE = $savedProfile
        }
    }

    foreach ($example in @(".env.example", "EchoMindJava\.env.example")) {
        $line = Select-String -LiteralPath (Join-Path $root $example) -Pattern "^DEEPSEEK_MODEL="
        Assert-Equal $line.Line "DEEPSEEK_MODEL=$expected" "$example has the native model default"
    }
    $script = Get-Content -LiteralPath $startup -Raw
    Assert-Equal $script.Contains("`"DEEPSEEK_MODEL`" `"$expected`"") $true "launcher has the native model default"
    Write-Output "Support chat launcher defaults and provider guard passed."
} finally {
    $resolved = (Resolve-Path -LiteralPath $testRoot).ProviderPath
    $parent = (Resolve-Path -LiteralPath $temp).ProviderPath.TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($resolved) -ceq $parent -and
            [IO.Path]::GetFileName($resolved) -match '^deskverify-support-provider-[0-9a-f-]{36}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
