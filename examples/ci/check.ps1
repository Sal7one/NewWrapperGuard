param([Parameter(Mandatory=$true)][string]$ProjectPath)
$ErrorActionPreference = 'Stop'
if (-not $env:WRAPPERGUARD_CLI -or -not [IO.Path]::IsPathFullyQualified($env:WRAPPERGUARD_CLI)) {
    throw 'Set WRAPPERGUARD_CLI to an absolute installed executable'
}
$cliArgs = @((Resolve-Path -LiteralPath $ProjectPath).Path, '--operation', 'build', '--enforce', '--json')
if ($env:WRAPPERGUARD_PROVIDERS) {
    if (-not [IO.Path]::IsPathFullyQualified($env:WRAPPERGUARD_PROVIDERS)) { throw 'Provider settings must be absolute and user-owned' }
    $cliArgs += @('--providers', $env:WRAPPERGUARD_PROVIDERS)
}
& $env:WRAPPERGUARD_CLI @cliArgs
exit $LASTEXITCODE
