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
    [string]$OutputDirectory = '',
    [switch]$RequireNode
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
if ($RequireNode -and -not $nodeCommand) {
    throw 'Node.js is required by -RequireNode but was not found.'
}
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$sourcePath = (Join-Path $engineRoot 'src') + [IO.Path]::PathSeparator + (Join-Path $acgnRoot 'src')
$compilerArguments = @(
    '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source',
    '-cp', (Join-Path $acgnRoot 'lib\*'), '-sourcepath', $sourcePath,
    '-d', $OutputDirectory,
    (Join-Path $engineRoot 'src\live\LiveFeedback.java'),
    (Join-Path $engineRoot 'src\live\EngineSelfTest.java')
)
& $JavaCompiler @compilerArguments
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed with exit code $LASTEXITCODE." }

$pythonCheck = @'
import ast, sys
from pathlib import Path
if sys.version_info < (3, 10):
    raise SystemExit('Python 3.10 or newer is required')
root = Path(sys.argv[1])
for name in ('server.py', 'luna.py', 'scripts/package_iis.py', 'deploy/iis/run_backend.py'):
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
