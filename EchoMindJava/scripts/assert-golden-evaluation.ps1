param(
    [Parameter(Mandatory = $true)]
    [string]$ReportDirectory,

    [Parameter(Mandatory = $true)]
    [string]$SurefireDirectory,

    [string]$DatasetVersion = "rag-support-golden-v1"
)

$ErrorActionPreference = "Stop"

$resolvedReportDirectory = [IO.Path]::GetFullPath($ReportDirectory)
$resolvedSurefireDirectory = [IO.Path]::GetFullPath($SurefireDirectory)

if (-not (Test-Path -LiteralPath $resolvedReportDirectory -PathType Container)) {
    throw "Golden report directory not found: $resolvedReportDirectory"
}

$reports = @(
    Get-ChildItem -LiteralPath $resolvedReportDirectory -Filter "*.json" -File
)
if ($reports.Count -ne 1) {
    throw "Expected exactly one Golden JSON report, found $($reports.Count)"
}

$report = Get-Content -LiteralPath $reports[0].FullName -Raw |
    ConvertFrom-Json
if ($report.dataset_version -ne $DatasetVersion) {
    throw "Unexpected Golden dataset version: $($report.dataset_version)"
}
if ($null -eq $report.gate) {
    throw "Golden report does not contain a release gate"
}
if ($report.gate.passed -ne $true) {
    $failed = @(
        $report.gate.checks |
            Where-Object { $_.blocking -eq $true -and $_.passed -ne $true } |
            ForEach-Object {
                "$($_.id): expected $($_.expectation), observed $($_.observed)"
            }
    )
    throw "Golden release gate failed:`n$($failed -join "`n")"
}

$suitePath = Join-Path `
    $resolvedSurefireDirectory `
    "TEST-com.echomind.saas.evaluation.golden.GoldenEvaluationEndToEndTest.xml"
if (-not (Test-Path -LiteralPath $suitePath -PathType Leaf)) {
    throw "Golden E2E Surefire report not found: $suitePath"
}

[xml]$surefire = Get-Content -LiteralPath $suitePath
$suite = $surefire.testsuite
$tests = [int]$suite.tests
$failures = [int]$suite.failures
$errors = [int]$suite.errors
$skipped = [int]$suite.skipped

if ($tests -ne 1) {
    throw "Expected one Golden E2E test, found $tests"
}
if ($failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
    throw "Golden E2E result is invalid: tests=$tests failures=$failures errors=$errors skipped=$skipped"
}

Write-Output (
    "Golden gate PASSED: dataset={0}, checks={1}, tests={2}, skipped={3}" -f
        $report.dataset_version,
        $report.gate.checks.Count,
        $tests,
        $skipped
)
