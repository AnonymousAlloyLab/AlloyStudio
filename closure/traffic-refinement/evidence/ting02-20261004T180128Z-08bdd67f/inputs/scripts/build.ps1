#Requires -Version 5.1
<#
.SYNOPSIS
Build the portable Java 17 engine and refresh the IIS archive on Windows.
.DESCRIPTION
Requires a JDK 17 or newer and Python 3.10 or newer. Node.js is optional for the
frontend syntax check; use -RequireNode to require it in a release build. The
IIS deployment archive already contains classes and needs only a Java runtime.
#>
[CmdletBinding()]
param(
    [string]$JavaCompiler = 'javac',
    [string]$Python = 'python',
    [string]$Node = 'node',
    [string]$ACGNRoot = '',
    [string]$OutputDirectory = '',
    [switch]$RequireNode,
    [switch]$EngineOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot 'build\engine\classes'
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot $OutputDirectory
}

# Resolve the executable before Python changes its compiler working directory.
# Relative JDK paths must retain the meaning they had in the calling shell.
$JavaCompiler = (Get-Command $JavaCompiler -CommandType Application -ErrorAction Stop |
    Select-Object -First 1).Source
$null = Get-Command $Python -ErrorAction Stop
$nodeCommand = Get-Command $Node -ErrorAction SilentlyContinue
if ($RequireNode -and -not $EngineOnly -and -not $nodeCommand) {
    throw 'Node.js is required by -RequireNode but was not found.'
}
$dependencyChecker = Join-Path $projectRoot 'runtime_dependencies.py'
if (-not (Test-Path -LiteralPath $dependencyChecker -PathType Leaf)) {
    throw 'Runtime dependency checker is missing. Copy the complete source distribution.'
}
$dependencyJson = & $Python '-E' '-s' $dependencyChecker '--root' $projectRoot '--dependencies-only'
$dependencyExit = $LASTEXITCODE
try { $dependencies = ($dependencyJson -join "`n") | ConvertFrom-Json }
catch { throw 'Runtime dependency check did not produce a valid report. Check Python 3.10+ and the source distribution.' }
if ($dependencyExit -ne 0 -or $dependencies.status -ne 'PASS') {
    $missing = @($dependencies.errors | ForEach-Object { $_.path }) -join ', '
    throw "Bundled Java dependencies are missing or changed: $missing. Restore vendor\acgn\lib from the complete distribution before building."
}
# SQLite is authoritative. The helper validates it, or migrates legacy inputs
# only when no database exists. Existing administrator additions are preserved.
if (-not $EngineOnly) {
    $prepareData = Join-Path $projectRoot 'scripts\prepare_private_data.py'
    if (-not (Test-Path -LiteralPath $prepareData -PathType Leaf)) {
        throw 'Exercise preparation helper is missing. Restore the complete source checkout.'
    }
    $prepareArguments = @('-E', '-s', $prepareData, '--root', $projectRoot)
    if (-not $ACGNRoot) { $ACGNRoot = $env:ACGN_ROOT }
    if ($ACGNRoot) { $prepareArguments += @('--source-root', $ACGNRoot) }
    & $Python @prepareArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Exercise database preparation failed; use the diagnostic above. Existing data and previous archives were preserved.'
    }
}
if ($EngineOnly) {
    & $Python '-E' '-s' (Join-Path $projectRoot 'scripts\build_engine.py') `
        '--root' $projectRoot '--javac' $JavaCompiler '--output' $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed. The IIS archive was not refreshed.' }
    Write-Output "Built Java 17 compatible engine classes in $OutputDirectory"
    return
}

$pythonCheck = @'
import ast, sys
from pathlib import Path
if sys.version_info < (3, 10):
    raise SystemExit('Python 3.10 or newer is required')
root = Path(sys.argv[1])
for name in ('server.py', 'luna.py', 'runtime_dependencies.py', 'exercise_store.py', 'exercise_sql.py', 'admin_auth.py', 'admin_upload.py', 'admin_luna.py', 'admin_service.py', 'scripts/configure_admin.py', 'scripts/manage_exercises.py', 'scripts/build_engine.py', 'scripts/package_iis.py', 'scripts/prepare_private_data.py', 'deploy/iis/run_backend.py'):
    ast.parse((root / name).read_text(encoding='utf-8'), filename=name)
'@
& $Python '-c' $pythonCheck $projectRoot
if ($LASTEXITCODE -ne 0) { throw "Python validation failed with exit code $LASTEXITCODE." }
if ($nodeCommand) {
    & $Node '--check' (Join-Path $projectRoot 'web\app.js')
    if ($LASTEXITCODE -ne 0) { throw "JavaScript validation failed with exit code $LASTEXITCODE." }
    & $Node '--check' (Join-Path $projectRoot 'web\instance-graph.js')
    if ($LASTEXITCODE -ne 0) { throw "Instance graph JavaScript validation failed with exit code $LASTEXITCODE." }
    & $Node '--check' (Join-Path $projectRoot 'web\admin\app.js')
    if ($LASTEXITCODE -ne 0) { throw "Admin JavaScript validation failed with exit code $LASTEXITCODE." }
} else {
    Write-Warning 'Node.js was not found; the optional JavaScript syntax check was skipped.'
}
# One dependency chain: validation -> clean compilation -> ZIP -> checksum.
# The native Python process constructs javac paths using Windows separators.
$packageJson = & $Python '-E' '-s' (Join-Path $projectRoot 'scripts\package_iis.py') `
    '--source' $projectRoot '--javac' $JavaCompiler '--classes-output' $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'IIS build failed. Use the diagnostic above; previous timestamped archives remain unchanged.' }
try { $package = ($packageJson -join "`n") | ConvertFrom-Json }
catch { throw 'Packaging did not return valid archive metadata. Check the output in build\iis before deploying.' }
Write-Output "Created IIS package: $($package.archive)"
Write-Output "SHA-256: $($package.sha256)"
