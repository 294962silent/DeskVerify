param(
    [string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) ".env"),
    [int]$ExpiresHours = 24,
    [string]$TenantId = "acme",
    [string]$UserId = "demo-acme-user"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "env-utils.ps1")

$values = Read-DotEnv -Path $EnvFile
$secret = [string]$values["ECHOMIND_JWT_HMAC_SECRET"]
if ([string]::IsNullOrWhiteSpace($secret)) {
    throw "ECHOMIND_JWT_HMAC_SECRET is missing from $EnvFile"
}

try {
    $secretBytes = [Convert]::FromBase64String($secret)
} catch {
    throw "ECHOMIND_JWT_HMAC_SECRET must be valid Base64"
}
if ($secretBytes.Length -lt 32) {
    throw "ECHOMIND_JWT_HMAC_SECRET must decode to at least 32 bytes"
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$headerJson = '{"alg":"HS256","typ":"JWT"}'
$payload = [ordered]@{
    iss = $(if ($values["ECHOMIND_JWT_ISSUER"]) { $values["ECHOMIND_JWT_ISSUER"] } else { "echomind-local" })
    aud = $(if ($values["ECHOMIND_JWT_AUDIENCE"]) { $values["ECHOMIND_JWT_AUDIENCE"] } else { "echomind-api" })
    sub = $UserId
    tenant_id = $TenantId
    iat = $now
    exp = $now + ($ExpiresHours * 3600)
    jti = [guid]::NewGuid().ToString()
} | ConvertTo-Json -Compress

$header = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($headerJson))
$body = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($payload))
$unsigned = "$header.$body"
$hmac = [Security.Cryptography.HMACSHA256]::new($secretBytes)
try {
    $signature = ConvertTo-Base64Url -Bytes (
        $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned))
    )
} finally {
    $hmac.Dispose()
}

Write-Output "$unsigned.$signature"
