#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PublicUrl,
    [string]$PythonExe = 'python',
    [ValidateRange(1, 30)][int]$TimeoutSeconds = 5
)
# No administrator privileges, installed task, IIS changes, or private config.
$ErrorActionPreference = 'Stop'
$diagnostic = Join-Path $PSScriptRoot 'test_api_connection.py'
& $PythonExe $diagnostic --public-url $PublicUrl --timeout $TimeoutSeconds
exit $LASTEXITCODE
