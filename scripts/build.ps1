#Requires -Version 5.1
<#
.SYNOPSIS
Build the portable Java 17 engine from the complete source checkout on Windows.
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
$acgnRoot = Join-Path $projectRoot 'vendor\acgn'
$engineRoot = Join-Path $projectRoot 'engine'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot 'build\engine\classes'
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot $OutputDirectory
}

$null = Get-Command $JavaCompiler -ErrorAction Stop
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
# Private corpus outputs are intentionally excluded from source control. When a
# fresh full-source build lacks either file, regenerate the pair from the
# explicitly supplied ACGN checkout (or its conventional sibling directory).
if (-not $EngineOnly) {
    $privateExercises = Join-Path $projectRoot 'exercises'
    $catalogue = Join-Path $privateExercises 'catalogue.json'
    $correctPools = Join-Path $privateExercises 'correct-pools.json'
    $hasCatalogue = Test-Path -LiteralPath $catalogue -PathType Leaf
    $hasPools = Test-Path -LiteralPath $correctPools -PathType Leaf
    if ($hasCatalogue -xor $hasPools) {
        throw 'The private catalogue and correct pools must both exist. Restore both, or remove both and rebuild with -ACGNRoot pointing to the original ACGN checkout.'
    }
    if (-not $hasCatalogue) {
        if (-not $ACGNRoot) { $ACGNRoot = $env:ACGN_ROOT }
        if (-not $ACGNRoot) { $ACGNRoot = Join-Path (Split-Path -Parent $projectRoot) 'ACGN' }
        $ACGNRoot = [IO.Path]::GetFullPath($ACGNRoot)
        if (-not (Test-Path -LiteralPath (Join-Path $ACGNRoot 'classified-data') -PathType Container)) {
            throw 'Private exercise data are absent from this checkout. Use -ACGNRoot with an original ACGN checkout containing classified-data, or restore the private catalogue and correct pools from a trusted deployment bundle.'
        }
        $prepareData = Join-Path $projectRoot 'scripts\prepare_private_data.py'
        if (-not (Test-Path -LiteralPath $prepareData -PathType Leaf)) {
            throw 'Private exercise data are absent and scripts\prepare_private_data.py is missing from this source checkout.'
        }
        & $Python '-E' '-s' $prepareData '--root' $projectRoot '--source-root' $ACGNRoot
        if ($LASTEXITCODE -ne 0) {
            throw 'Private corpus import failed. Check the original ACGN classified-data and its exercise sources.'
        }
    }
}
# Use explicit absolute JAR paths as a single argument. No wildcard expansion,
# current directory, machine CLASSPATH, Maven cache, or upstream checkout is needed.
$dependencyClassPath = @($dependencies.dependencies | ForEach-Object {
    Join-Path $projectRoot $_.path
}) -join [IO.Path]::PathSeparator
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$sourcePath = (Join-Path $engineRoot 'src') + [IO.Path]::PathSeparator + (Join-Path $acgnRoot 'src')
$compilerArguments = @(
    '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source',
    '-cp', $dependencyClassPath, '-sourcepath', $sourcePath,
    '-d', $OutputDirectory,
    (Join-Path $engineRoot 'src\live\LiveFeedback.java'),
    (Join-Path $engineRoot 'src\live\EngineSelfTest.java')
)
& $JavaCompiler @compilerArguments
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed with exit code $LASTEXITCODE." }
if ($EngineOnly) {
    Write-Output "Built Java 17 compatible engine classes in $OutputDirectory"
    return
}

$pythonCheck = @'
import ast, sys
from pathlib import Path
if sys.version_info < (3, 10):
    raise SystemExit('Python 3.10 or newer is required')
root = Path(sys.argv[1])
for name in ('server.py', 'luna.py', 'runtime_dependencies.py', 'scripts/package_iis.py', 'scripts/prepare_private_data.py', 'deploy/iis/run_backend.py'):
    ast.parse((root / name).read_text(encoding='utf-8'), filename=name)
'@
& $Python '-c' $pythonCheck $projectRoot
if ($LASTEXITCODE -ne 0) { throw "Python validation failed with exit code $LASTEXITCODE." }
if ($nodeCommand) {
    & $Node '--check' (Join-Path $projectRoot 'web\app.js')
    if ($LASTEXITCODE -ne 0) { throw "JavaScript validation failed with exit code $LASTEXITCODE." }
} else {
    Write-Warning 'Node.js was not found; the optional JavaScript syntax check was skipped.'
}
Write-Output "Built Java 17 compatible engine classes in $OutputDirectory"
