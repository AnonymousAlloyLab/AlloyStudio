# Linux PowerShell policy regression harness. Windows path normalization and
# native filesystem calls are fixture adapters; this is NOT a Windows test.
# Native fixtures are in iis_paths.Tests.ps1.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy/iis/Common.ps1')
if ([IO.Path]::DirectorySeparatorChar -ne '/') { throw 'Run this policy fixture on Linux; use iis_paths.Tests.ps1 on Windows.' }

# Compile the actual PS5.1-compatible interop declaration without calling Win32.
Initialize-FinalPathResolver
$originalNormalizer = ${function:ConvertTo-LocalPathText}
$checks = 0
function Assert-Equal {
    param($Actual, $Expected)
    if ($Actual -cne $Expected) { throw "Expected '$Expected'; got '$Actual'." }
    $script:checks++
}
function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    $failure = $null
    try { & $Action | Out-Null } catch { $failure = $_.Exception.Message }
    if (-not $failure -or -not $failure.Contains($Message)) {
        throw "Expected rejection containing '$Message'; received '$failure'."
    }
    $script:checks++
}

foreach ($invalid in @('', 'C:relative', '\\server\share', 'https://example.invalid',
        'C:\a"b', 'C:\a*', 'C:\a:stream', "C:\a`nb")) {
    Assert-Rejected { & $originalNormalizer -Path $invalid -Purpose 'Input' } 'absolute local drive path'
}

# The production traversal and policies run unchanged. Only OS operations are
# replaced. /C represents a normalized drive and allows .NET parent traversal.
function ConvertTo-LocalPathText { param([string]$Path, [string]$Purpose); return $Path }
$entries = @{}
$targets = @{}
function Add-Fixture {
    param([string]$Path, [bool]$Directory = $true, [string]$LinkType = '', [string]$Target = '')
    $attributes = [IO.FileAttributes]::Normal
    if ($LinkType) { $attributes = [IO.FileAttributes]::ReparsePoint }
    $entries[$Path] = [pscustomobject]@{
        PSIsContainer = $Directory; Attributes = $attributes; LinkType = $LinkType
    }
    if ($Target) { $targets[$Path] = $Target }
}
function Get-PathEntry {
    param([string]$Path)
    if ($Path -eq '/C/denied') { throw 'access denied fixture' }
    return $entries[$Path]
}
function Get-FinalLocalPath {
    param([string]$Path, [string]$Purpose)
    if ($Path -in @('/C/dangling', '/C/cycle')) { throw 'native resolution failed fixture' }
    if ($targets.ContainsKey($Path)) { return $targets[$Path] }
    return $Path
}
Add-Fixture '/'
Add-Fixture '/C'
Add-Fixture '/C/actual'
Add-Fixture '/C/actual/java.exe' $false
Add-Fixture '/C/java.exe' $false 'SymbolicLink' '/C/actual/java.exe'
Add-Fixture '/C/runtime' $true 'Junction' '/C/actual'
Add-Fixture '/C/runtime/java.exe' $false '' '/C/actual/java.exe'
Add-Fixture '/C/public'
Add-Fixture '/C/private'
Add-Fixture '/C/other-site' $true 'Junction' '/C/public'
Add-Fixture '/C/exposing-site' $true 'Junction' '/C/private'
Add-Fixture '/C/PRIVATE~1' $true '' '/C/private'
Add-Fixture '/C/dangling' $true 'Junction'
Add-Fixture '/C/cycle' $false 'SymbolicLink'
Add-Fixture '/C/python.exe' $false 'AppExecLink'
$entries['/C/no-metadata'] = [pscustomobject]@{
    PSIsContainer = $false; Attributes = [IO.FileAttributes]::ReparsePoint
}

Assert-Equal (Get-ResolvedLocalPath '/C/actual/java.exe' -PathType Leaf) '/C/actual/java.exe'
Assert-Equal (Get-ResolvedLocalPath '/C/java.exe' -PathType Leaf) '/C/actual/java.exe'
Assert-Equal (Get-ResolvedLocalPath '/C/runtime/java.exe' -PathType Leaf) '/C/actual/java.exe'
Assert-Equal (Get-ResolvedLocalPath '/C/other-site' -PathType Container) '/C/public'
Assert-Equal (Get-ResolvedLocalPath '/C/other-site/future/nested' -PathType Container) '/C/public/future/nested'
Assert-Equal (Get-LocalPath '/C/private/future/nested') '/C/private/future/nested'
Assert-Equal (Get-LocalPath '/C/PRIVATE~1') '/C/private'
Assert-Rejected { Get-LocalPath '/C/runtime/future/key' -Purpose 'KeyFile' } '/C/runtime'
Assert-Rejected { Get-LocalPath '/C/java.exe' -Purpose 'PrivateFile' } 'PrivateFile'
Assert-Rejected { Get-ResolvedLocalPath '/C/python.exe' -Purpose 'PythonExe' -PathType Leaf } 'unsupported reparse point'
Assert-Rejected { Get-ResolvedLocalPath '/C/no-metadata' -PathType Leaf } 'unsupported reparse point'
Assert-Rejected { Get-ResolvedLocalPath '/C/dangling/missing' } 'native resolution failed fixture'
Assert-Rejected { Get-ResolvedLocalPath '/C/cycle' -PathType Leaf } 'native resolution failed fixture'
Assert-Rejected { Get-ResolvedLocalPath '/C/denied/missing' } 'access denied fixture'
Assert-Rejected { Get-ResolvedLocalPath '/C/runtime/missing.exe' -PathType Leaf } 'does not exist'
Assert-Rejected { Get-ResolvedLocalPath '/C/actual/java.exe' -PathType Container } 'expected a directory'
Assert-Rejected { Get-ResolvedLocalPath '/C/actual/java.exe/child' } 'expected a directory'
Assert-Rejected { Get-ResolvedLocalPath '/C/actual' -PathType Leaf } 'expected an executable file'

# Exercise the real IIS-root enumeration against multiple controlled sites.
function Import-Module { param($Name, $ErrorAction) }
function Get-WebConfigurationProperty {
    param($PSPath, $Filter, $Name)
    return @([pscustomobject]@{Value='/C/other-site'}, [pscustomobject]@{Value='/C/exposing-site'})
}
$publicRoots = @(Get-IisPhysicalRoots)
Assert-Equal ($publicRoots -join ',') '/C/public,/C/private'
Assert-Rejected { Assert-PrivatePath '/C/private' $publicRoots } 'outside every IIS physical directory'
# Path containment itself uses native Windows separators; check both overlap
# directions and ensure similar prefixes belonging to other sites are allowed.
Assert-Equal (Test-WithinPath 'C:\Private\keys' 'c:\PRIVATE') $true
Assert-Equal (Test-WithinPath 'C:\PrivateOther' 'C:\Private') $false
Assert-Rejected { Assert-PrivatePath 'C:\Private' @('C:\Private\public') } 'outside every IIS physical directory'
Assert-Rejected { Assert-PrivatePath 'C:\Private\keys' @('C:\Private') } 'outside every IIS physical directory'
Assert-PrivatePath 'C:\PrivateOther' @('C:\Private')
$checks++

[ordered]@{
    status='PASS'; checks=$checks; native_windows=$false; filesystem='controlled fixtures'
    powershell_version=$PSVersionTable.PSVersion.ToString()
    common_sha256=(Get-FileHash (Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy/iis/Common.ps1') -Algorithm SHA256).Hash
    fixture_sha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
} | ConvertTo-Json
