#Requires -Version 5.1
<#
Native Windows regression tests for IIS path handling. Run from a source checkout:
  powershell.exe -NoProfile -File .\tests\iis_paths.Tests.ps1
Creating file symlinks requires elevation or Windows Developer Mode. This harness
does not install services, change IIS configuration, alter ACLs, or read keys.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$caseResults = New-Object 'System.Collections.Generic.List[object]'
$fixtureLinks = New-Object 'System.Collections.Generic.List[object]'
$fixtureRoot = $null
$infrastructureError = $null

function Limit-ErrorText {
    param([string]$Text)
    $textValue = $Text -replace '[\r\n]+', ' '
    if ($textValue.Length -gt 400) { return $textValue.Substring(0, 400) }
    return $textValue
}

function Assert-EqualPath {
    param([string]$Actual, [string]$Expected)
    if (-not $Actual.Equals($Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw ('Expected path "' + $Expected + '", received "' + $Actual + '".')
    }
}

function Assert-PathRejected {
    param([scriptblock]$Action, [string[]]$MessageContains = @())
    $rejection = $null
    try { & $Action | Out-Null }
    catch { $rejection = $_.Exception.Message }
    if ($null -eq $rejection) { throw 'Expected the path to be rejected.' }
    foreach ($expectedText in $MessageContains) {
        if ($rejection.IndexOf($expectedText, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw ('Rejection did not identify "' + $expectedText + '": ' + $rejection)
        }
    }
}

function Invoke-PathCase {
    param([string]$Name, [scriptblock]$Action)
    try {
        & $Action | Out-Null
        $caseResults.Add([ordered]@{ name = $Name; status = 'PASS' })
    } catch {
        $caseResults.Add([ordered]@{
            name = $Name; status = 'FAIL'; error = (Limit-ErrorText $_.Exception.Message)
        })
    }
}

function New-TestFileLink {
    param([string]$Path, [string]$Target)
    [AlloyStudioPathTestNative]::CreateFileLink($Path, $Target)
    $fixtureLinks.Add([pscustomobject]@{ Path = $Path; Directory = $false })
}

function New-TestJunction {
    param([string]$Path, [string]$Target)
    New-Item -ItemType Junction -Path $Path -Target $Target -ErrorAction Stop | Out-Null
    $fixtureLinks.Add([pscustomobject]@{ Path = $Path; Directory = $true })
}

try {
    if ($env:OS -ne 'Windows_NT') {
        throw 'Native Windows is required; PowerShell parsing on Linux does not execute these filesystem tests.'
    }
    . (Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy\iis\Common.ps1')
    if (-not ('AlloyStudioPathTestNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
public static class AlloyStudioPathTestNative {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    private static extern bool CreateSymbolicLinkW(string link, string target, uint flags);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetShortPathNameW(string path, StringBuilder result, uint length);
    public static void CreateFileLink(string path, string target) {
        // Flag 2 uses Developer Mode when available. Older Windows rejects it;
        // retry without the flag to support elevated Windows Server sessions.
        if (CreateSymbolicLinkW(path, target, 2)) return;
        int error = Marshal.GetLastWin32Error();
        if (error == 87 && CreateSymbolicLinkW(path, target, 0)) return;
        error = Marshal.GetLastWin32Error();
        throw new Win32Exception(error, "Cannot create test symlinks. Run elevated or enable Windows Developer Mode.");
    }
    public static string ShortPath(string path) {
        StringBuilder result = new StringBuilder(32768);
        uint length = GetShortPathNameW(path, result, (uint)result.Capacity);
        return length > 0 && length < result.Capacity ? result.ToString() : null;
    }
}
'@
    }

    # Use a resolved temporary parent so an unrelated redirected TEMP directory
    # does not invalidate the strict-path test fixtures themselves.
    $temporaryParent = Get-ResolvedLocalPath -Path ([IO.Path]::GetTempPath()) -PathType Container
    $fixtureRoot = Join-Path $temporaryParent ('Alloy IIS path tests ' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $runtimeDirectory = Join-Path $fixtureRoot 'Real Runtime With Spaces'
    $privateDirectory = Join-Path $fixtureRoot 'Private Backend'
    $publicDirectory = Join-Path $fixtureRoot 'Other Website'
    foreach ($directory in @($runtimeDirectory, $privateDirectory, $publicDirectory)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
    $runtimeFile = Join-Path $runtimeDirectory 'java.exe'
    [IO.File]::WriteAllText($runtimeFile, 'inert test fixture; never execute')
    $executableLink = Join-Path $fixtureRoot 'java alias.exe'
    $runtimeJunction = Join-Path $fixtureRoot 'Runtime Junction'
    $publicJunction = Join-Path $fixtureRoot 'IIS Other Junction'
    $exposedPrivateJunction = Join-Path $fixtureRoot 'IIS Private Junction'
    $danglingLink = Join-Path $fixtureRoot 'missing-target.exe'
    $cycleFirst = Join-Path $fixtureRoot 'cycle-first.exe'
    $cycleSecond = Join-Path $fixtureRoot 'cycle-second.exe'
    New-TestFileLink -Path $executableLink -Target $runtimeFile
    New-TestJunction -Path $runtimeJunction -Target $runtimeDirectory
    New-TestJunction -Path $publicJunction -Target $publicDirectory
    New-TestJunction -Path $exposedPrivateJunction -Target $privateDirectory
    New-TestFileLink -Path $danglingLink -Target (Join-Path $fixtureRoot 'does-not-exist.exe')
    New-TestFileLink -Path $cycleFirst -Target $cycleSecond
    New-TestFileLink -Path $cycleSecond -Target $cycleFirst

    Invoke-PathCase 'ordinary runtime file with spaces' {
        Assert-EqualPath (Get-ResolvedLocalPath -Path $runtimeFile -Purpose 'JavaExe' -PathType Leaf) $runtimeFile
    }
    Invoke-PathCase 'strict private directory remains canonical' {
        Assert-EqualPath (Get-LocalPath -Path $privateDirectory -Purpose 'BackendRoot') $privateDirectory
    }
    Invoke-PathCase 'runtime executable symbolic link' {
        Assert-EqualPath (Get-ResolvedLocalPath -Path $executableLink -Purpose 'JavaExe' -PathType Leaf) $runtimeFile
    }
    Invoke-PathCase 'runtime executable parent junction' {
        Assert-EqualPath (Get-ResolvedLocalPath -Path (Join-Path $runtimeJunction 'java.exe') -PathType Leaf) $runtimeFile
    }
    Invoke-PathCase 'unrelated IIS junction permits private backend' {
        $resolvedPublic = Get-ResolvedLocalPath -Path $publicJunction -Purpose 'IIS physical root' -PathType Container
        Assert-EqualPath $resolvedPublic $publicDirectory
        Assert-PrivatePath -Path (Get-LocalPath $privateDirectory) -PublicRoots @($resolvedPublic)
    }
    Invoke-PathCase 'IIS junction exposing private backend is rejected' {
        $resolvedPublic = Get-ResolvedLocalPath -Path $exposedPrivateJunction -PathType Container
        Assert-PathRejected { Assert-PrivatePath -Path (Get-LocalPath $privateDirectory) -PublicRoots @($resolvedPublic) }
    }
    Invoke-PathCase 'private backend below public junction is rejected' {
        $resolvedPublic = Get-ResolvedLocalPath -Path $exposedPrivateJunction -PathType Container
        Assert-PathRejected {
            Assert-PrivatePath -Path (Get-LocalPath (Join-Path $privateDirectory 'future secrets')) -PublicRoots @($resolvedPublic)
        }
    }
    Invoke-PathCase 'missing public suffix below junction resolves' {
        $requested = Join-Path $publicJunction 'future\nested'
        $expected = Join-Path $publicDirectory 'future\nested'
        Assert-EqualPath (Get-ResolvedLocalPath -Path $requested -PathType Container) $expected
        Assert-EqualPath (Get-ResolvedLocalPath -Path $requested) $expected
    }
    Invoke-PathCase 'missing runtime executable is rejected' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path (Join-Path $runtimeJunction 'missing.exe') -PathType Leaf }
    }
    Invoke-PathCase 'file cannot be used as public directory' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path $runtimeFile -PathType Container }
    }
    Invoke-PathCase 'directory cannot be used as runtime file' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path $runtimeDirectory -PathType Leaf }
    }
    Invoke-PathCase 'dangling link is rejected even for a missing-capable path' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path $danglingLink }
    }
    Invoke-PathCase 'cyclic symbolic links are rejected' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path $cycleFirst -PathType Leaf }
    }
    Invoke-PathCase 'strict link rejection identifies actual offending ancestor' {
        Assert-PathRejected {
            Get-LocalPath -Path (Join-Path $runtimeJunction 'future\secret.key') -Purpose 'KeyFile'
        } -MessageContains @($runtimeJunction, 'KeyFile')
    }
    Invoke-PathCase 'strict file symbolic link remains rejected' {
        Assert-PathRejected { Get-LocalPath -Path $executableLink -Purpose 'PrivateFile' } -MessageContains @($executableLink)
    }
    Invoke-PathCase 'UNC path is rejected without attempting remote resolution' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path '\\alloy-fixture.invalid\share\java.exe' -PathType Leaf }
    }
    Invoke-PathCase 'drive-relative path is rejected' {
        Assert-PathRejected { Get-ResolvedLocalPath -Path 'C:java.exe' -PathType Leaf }
    }

    $shortPrivate = [AlloyStudioPathTestNative]::ShortPath($privateDirectory)
    $shortRuntime = [AlloyStudioPathTestNative]::ShortPath($runtimeFile)
    if ($shortPrivate -and $shortRuntime -and
        -not $shortPrivate.Equals($privateDirectory, [StringComparison]::OrdinalIgnoreCase) -and
        -not $shortRuntime.Equals($runtimeFile, [StringComparison]::OrdinalIgnoreCase)) {
        Invoke-PathCase '8.3 executable alias resolves to long path' {
            Assert-EqualPath (Get-ResolvedLocalPath -Path $shortRuntime -PathType Leaf) $runtimeFile
        }
        Invoke-PathCase '8.3 private alias cannot bypass public overlap check' {
            $actualPrivate = Get-LocalPath -Path $shortPrivate
            Assert-EqualPath $actualPrivate $privateDirectory
            Assert-PathRejected {
                Assert-PrivatePath -Path $actualPrivate -PublicRoots @($privateDirectory)
            }
        }
    } else {
        $caseResults.Add([ordered]@{
            name = '8.3 alias coverage'; status = 'SKIP'; reason = 'No distinct short aliases exist on this volume.'
        })
    }
} catch {
    $infrastructureError = Limit-ErrorText $_.Exception.Message
} finally {
    # Remove links explicitly before recursively removing the private fixture.
    # Directory.Delete(nonrecursive) removes the junction itself, never targets.
    $cleanupFailed = $false
    for ($index = $fixtureLinks.Count - 1; $index -ge 0; $index--) {
        $link = $fixtureLinks[$index]
        try {
            if ($link.Directory) { [IO.Directory]::Delete($link.Path, $false) }
            else { [IO.File]::Delete($link.Path) }
        } catch {
            $cleanupFailed = $true
            $infrastructureError = 'Fixture link cleanup failed; recursive cleanup was withheld to protect link targets.'
        }
    }
    if ($fixtureRoot -and -not $cleanupFailed -and [IO.Directory]::Exists($fixtureRoot)) {
        try { [IO.Directory]::Delete($fixtureRoot, $true) }
        catch { $infrastructureError = 'Fixture cleanup failed: ' + (Limit-ErrorText $_.Exception.Message) }
    }
}

$failures = @($caseResults | Where-Object { $_.status -eq 'FAIL' }).Count
$passed = @($caseResults | Where-Object { $_.status -eq 'PASS' }).Count
$skipped = @($caseResults | Where-Object { $_.status -eq 'SKIP' }).Count
$status = 'PASS'
$exitCode = 0
if ($infrastructureError) { $status = 'INFRASTRUCTURE_FAILURE'; $exitCode = 2 }
elseif ($failures -gt 0) { $status = 'FAIL'; $exitCode = 1 }
[ordered]@{
    status = $status
    native_windows = ($env:OS -eq 'Windows_NT')
    powershell_version = $PSVersionTable.PSVersion.ToString()
    passed = $passed
    failed = $failures
    skipped = $skipped
    error = $infrastructureError
    cases = @($caseResults.ToArray())
} | ConvertTo-Json -Depth 5
exit $exitCode
