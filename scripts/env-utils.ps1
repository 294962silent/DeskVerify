function Read-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*([^=]+)=(.*)$') {
            continue
        }
        $name = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and
                (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                 ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

function Write-DotEnv {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Values
    )

    $lines = foreach ($name in $Values.Keys) {
        "$name=$($Values[$name])"
    }
    [IO.File]::WriteAllLines(
        [IO.Path]::GetFullPath($Path),
        $lines,
        [Text.UTF8Encoding]::new($false)
    )
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).
        TrimEnd('=').
        Replace('+', '-').
        Replace('/', '_')
}
