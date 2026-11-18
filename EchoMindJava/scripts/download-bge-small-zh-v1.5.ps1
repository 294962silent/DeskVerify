param(
    [string]$Target = (Join-Path $PSScriptRoot "..\models\bge-small-zh-v1.5")
)

$ErrorActionPreference = "Stop"
$base = "https://modelscope.cn/models/lyf219/bge-small-zh-v1.5-onnx/resolve/master"
$curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($null -eq $curlCommand) {
    $curlCommand = Get-Command curl -ErrorAction Stop
}
$files = @(
    @{ Name = "model.onnx"; Url = "bge-small-zh-v1.5.onnx"; Sha256 = "e256a606233996478f0eb6c99c9814e3797043d4a5563b4646b10929546196e5" },
    @{ Name = "tokenizer.json"; Url = "tokenizer.json"; Sha256 = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26" },
    @{ Name = "vocab.txt"; Url = "vocab.txt"; Sha256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c" },
    @{ Name = "config.json"; Url = "config.json"; Sha256 = "a7ef8b7733d4d54670d88617be0a426b56152296844e5ff603ef6fda92633da0" },
    @{ Name = "LICENSE"; Url = "LICENSE"; Sha256 = "3cbe701a5c973f0f5bd10daad2433b81bdd5071344d70daeaf8b7ee204c80e35" }
)

$Target = [IO.Path]::GetFullPath($Target)
New-Item -ItemType Directory -Force -Path $Target | Out-Null

foreach ($file in $files) {
    $path = Join-Path $Target $file.Name
    if (-not (Test-Path -LiteralPath $path)) {
        $temporary = "$path.download"
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        & $curlCommand.Source -L --fail --retry 3 --max-time 600 `
            -o $temporary "$base/$($file.Url)"
        if ($LASTEXITCODE -ne 0) {
            throw "download failed for $($file.Name) with exit code $LASTEXITCODE"
        }
        Move-Item -LiteralPath $temporary -Destination $path -Force
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
    if ($actual -ne $file.Sha256) {
        throw "SHA-256 mismatch for $($file.Name): $actual"
    }
}

Write-Output "BGE model ready at $Target"
