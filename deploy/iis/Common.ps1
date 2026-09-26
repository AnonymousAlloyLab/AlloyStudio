# Shared by the Windows administrator scripts; never dot-source untrusted copies.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Windows {
    if ($env:OS -ne 'Windows_NT') { throw 'This script requires Windows and IIS 10.0.' }
}

function Initialize-FinalPathResolver {
    if ('AlloyStudio.Deployment.NativePath' -as [type]) { return }
    # .NET Framework / Windows PowerShell 5.1 has no ResolveLinkTarget API.
    # Open the target, not the reparse point, so Windows resolves parent links
    # and short (8.3) names too. No file data or credentials are read.
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
namespace AlloyStudio.Deployment {
    public static class NativePath {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFileW(string path, uint access,
            uint share, IntPtr security, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle,
            StringBuilder path, uint length, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFileAttributesW(string path);
        public static bool EntryExists(string path) {
            // Attributes describe the link itself even when its target is gone.
            if (GetFileAttributesW(path) != 0xffffffff) return true;
            int error = Marshal.GetLastWin32Error();
            if (error == 2 || error == 3) return false;
            throw new Win32Exception(error);
        }
        public static string Resolve(string path) {
            using (SafeFileHandle handle = CreateFileW(path, 0, 7, IntPtr.Zero,
                    3, 0x02000000, IntPtr.Zero)) {
                if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                int capacity = 512;
                while (capacity <= 32768) {
                    var buffer = new StringBuilder(capacity);
                    uint length = GetFinalPathNameByHandleW(handle, buffer, (uint)capacity, 0);
                    if (length == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
                    if (length < capacity) return buffer.ToString();
                    capacity = checked((int)length + 1);
                }
                throw new InvalidOperationException("Resolved path is too long.");
            }
        }
    }
}
'@
}

function ConvertTo-LocalPathText {
    param([string]$Path, [string]$Purpose)
    if ($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path.Substring(2) -match '[:*?"<>|\r\n]') {
        throw "$Purpose '$Path': use an absolute local drive path without wildcards, quotes, or newlines."
    }
    $result = [IO.Path]::GetFullPath($Path)
    if ($result.Length -gt 3) { $result = $result.TrimEnd('\', '/') }
    return $result
}

function Get-PathEntry {
    param([string]$Path)
    try { return Get-Item -LiteralPath $Path -Force -ErrorAction Stop }
    catch [System.Management.Automation.ItemNotFoundException] {
        # Some Windows provider versions report a dangling link as not found.
        # Only a truly absent directory entry may become a missing suffix.
        Initialize-FinalPathResolver
        if ([AlloyStudio.Deployment.NativePath]::EntryExists($Path)) {
            throw "Cannot inspect path '$Path'; it may be a broken junction or symbolic link."
        }
        return $null
    }
    # Access denied and other inspection failures must not count as missing.
}

function Get-FinalLocalPath {
    param([string]$Path, [string]$Purpose)
    Initialize-FinalPathResolver
    try { $final = [AlloyStudio.Deployment.NativePath]::Resolve($Path) }
    catch { throw "$Purpose '$Path': Windows could not resolve this path. Check for a broken link, a link cycle, an inaccessible target, or a Microsoft Store execution alias; use the real installed executable or directory." }
    if ($final -notmatch '^\\\\\?\\[A-Za-z]:\\') {
        throw "$Purpose '$Path': the resolved target is not a local drive path. UNC and device paths are unsupported."
    }
    $final = ConvertTo-LocalPathText -Path $final.Substring(4) -Purpose $Purpose
    $drive = New-Object IO.DriveInfo([IO.Path]::GetPathRoot($final))
    if ($drive.DriveType -notin @([IO.DriveType]::Fixed, [IO.DriveType]::Removable, [IO.DriveType]::Ram)) {
        throw "$Purpose '$Path': the resolved target must be on a local drive, not a mapped network drive."
    }
    $ancestor = $final
    while ($ancestor) {
        $item = Get-PathEntry -Path $ancestor
        if ($null -eq $item -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "$Purpose '$Path': resolved target '$ancestor' is unavailable or still a reparse point. Use a regular local target."
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $final
}

function Resolve-DeploymentPath {
    param([string]$Path, [string]$Purpose, [switch]$AllowLinks,
        [ValidateSet('Any', 'Leaf', 'Container')][string]$PathType = 'Any')
    $result = ConvertTo-LocalPathText -Path $Path -Purpose $Purpose
    if ($result.Length -le 3) { throw "$Purpose '$Path': a drive root cannot be used as an application directory." }
    $ancestor = $result
    $existingPath = $null
    $suffix = New-Object 'System.Collections.Generic.List[string]'
    while ($ancestor) {
        $item = Get-PathEntry -Path $ancestor
        if ($null -ne $item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                if (-not $AllowLinks) {
                    throw "$Purpose '$Path' passes through junction or symbolic link '$ancestor'. Use the real directory path or move the Alloy bundle to a regular local directory."
                }
                $linkType = $item.PSObject.Properties['LinkType']
                if ($null -eq $linkType -or $linkType.Value -notin @('SymbolicLink', 'Junction')) {
                    throw "$Purpose '$Path' passes through unsupported reparse point '$ancestor'. Use the real installed executable or directory; Microsoft Store execution aliases are unsupported."
                }
            }
            if (-not $existingPath) { $existingPath = $ancestor }
        } elseif (-not $existingPath) {
            $suffix.Insert(0, [IO.Path]::GetFileName($ancestor))
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    if (-not $existingPath) { throw "$Purpose '$Path': no accessible local parent directory was found." }
    if ($PathType -eq 'Leaf' -and $suffix.Count) { throw "$Purpose '$Path': the executable file does not exist." }
    # Resolve even link-free private paths, so 8.3 aliases cannot bypass the
    # public/private overlap check. A dangling link is opened here and fails;
    # it must never be mistaken for an ordinary missing child directory.
    $result = Get-FinalLocalPath -Path $existingPath -Purpose $Purpose
    $resolvedItem = Get-PathEntry -Path $result
    if ($null -eq $resolvedItem) { throw "$Purpose '$Path': the resolved target is unavailable." }
    if (($suffix.Count -or $PathType -eq 'Container') -and -not $resolvedItem.PSIsContainer) {
        throw "$Purpose '$Path': expected a directory."
    }
    if ($PathType -eq 'Leaf' -and $resolvedItem.PSIsContainer) { throw "$Purpose '$Path': expected an executable file." }
    foreach ($part in $suffix) { $result = Join-Path $result $part }
    return $result
}

function Get-LocalPath {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$Purpose = 'Deployment path')
    return Resolve-DeploymentPath -Path $Path -Purpose $Purpose
}

function Get-ResolvedLocalPath {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$Purpose = 'Runtime or IIS path',
        [ValidateSet('Any', 'Leaf', 'Container')][string]$PathType = 'Any')
    return Resolve-DeploymentPath -Path $Path -Purpose $Purpose -AllowLinks -PathType $PathType
}

function Test-WithinPath {
    param([string]$Path, [string]$Root)
    return $Path.Equals($Root, [StringComparison]::OrdinalIgnoreCase) -or
        $Path.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Assert-PrivatePath {
    param([string]$Path, [string[]]$PublicRoots)
    foreach ($publicRoot in $PublicRoots) {
        if ((Test-WithinPath -Path $Path -Root $publicRoot) -or
            (Test-WithinPath -Path $publicRoot -Root $Path)) {
            throw 'Private backend, runtime, logs, scripts, and key paths must be outside every IIS physical directory.'
        }
    }
}

function Set-RestrictedAcl {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [ValidateSet('ReadAndExecute', 'Modify')][string]$LocalServiceAccess = 'ReadAndExecute',
        [switch]$Recurse
    )
    $items = @((Get-Item -LiteralPath $Path -Force))
    if ($Recurse -and $items[0].PSIsContainer) {
        $items += @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    }
    foreach ($item in $items) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Cannot assign private ACLs through junction or symbolic link '$($item.FullName)'."
        }
        if ($item.PSIsContainer) {
            $acl = New-Object Security.AccessControl.DirectorySecurity
            $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        } else {
            $acl = New-Object Security.AccessControl.FileSecurity
            $inheritance = [Security.AccessControl.InheritanceFlags]::None
        }
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($entry in @(
            @('S-1-5-18', 'FullControl'),         # SYSTEM
            @('S-1-5-32-544', 'FullControl'),     # Builtin Administrators
            @('S-1-5-19', $LocalServiceAccess)    # LOCAL SERVICE
        )) {
            $sid = New-Object Security.Principal.SecurityIdentifier($entry[0])
            $rule = New-Object Security.AccessControl.FileSystemAccessRule(
                $sid, [Security.AccessControl.FileSystemRights]$entry[1], $inheritance,
                [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
    }
}

function Get-IisPhysicalRoots {
    Import-Module WebAdministration -ErrorAction Stop
    $values = @(Get-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' `
        -Filter 'system.applicationHost/sites/site/application/virtualDirectory' -Name physicalPath)
    return @($values | ForEach-Object {
        Get-ResolvedLocalPath -Path ([Environment]::ExpandEnvironmentVariables([string]$_.Value)) `
            -Purpose 'IIS physical directory' -PathType Container
    })
}

function Get-PublicOrigin {
    param([string]$PublicUrl)
    $uri = $null
    if ($PublicUrl -match '\s' -or
        -not [Uri]::TryCreate($PublicUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('https', 'http') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -notmatch '^/[A-Za-z0-9/._~-]*$') {
        throw 'PublicUrl must be an absolute HTTP(S) application URL without credentials, query, or fragment.'
    }
    if ($uri.Scheme -eq 'http' -and -not $uri.IsLoopback) {
        throw 'Use HTTPS for a public URL; HTTP is accepted only for localhost deployment checks.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Invoke-RuntimeDependencyCheck {
    param(
        [Parameter(Mandatory = $true)][string]$PythonExe,
        [Parameter(Mandatory = $true)][string]$BackendRoot,
        [Parameter(Mandatory = $true)][string]$JavaExe
    )
    $PythonExe = Get-ResolvedLocalPath -Path $PythonExe -Purpose 'PythonExe' -PathType Leaf
    $BackendRoot = Get-LocalPath -Path $BackendRoot -Purpose 'BackendRoot'
    $JavaExe = Get-ResolvedLocalPath -Path $JavaExe -Purpose 'JavaExe' -PathType Leaf
    $checker = Get-LocalPath -Path (Join-Path $BackendRoot 'runtime_dependencies.py')
    foreach ($requiredFile in @($PythonExe, $JavaExe, $checker)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            throw ('Runtime dependency preflight requires: ' + [IO.Path]::GetFileName($requiredFile))
        }
    }
    try {
        # The checker emits sanitized JSON, reads no credentials, and bounds the
        # fresh JVM test to 30 seconds. Ignore Python user-site/environment hooks.
        $output = (& $PythonExe '-E' '-s' $checker '--root' $BackendRoot '--java' $JavaExe 2>$null) -join "`n"
        $checkerExit = $LASTEXITCODE
        $dependencyReport = $output | ConvertFrom-Json
        if (-not $dependencyReport -or $dependencyReport.status -notin @('PASS', 'FAIL')) {
            throw 'Invalid dependency checker result.'
        }
    } catch {
        throw 'Runtime dependency checker could not return a valid report. Check the configured Python executable and complete backend distribution.'
    }
    if ($checkerExit -ne 0 -or $dependencyReport.status -ne 'PASS' -or
        $dependencyReport.engine.status -ne 'PASS' -or $dependencyReport.engine.checks -lt 1) {
        $details = @($dependencyReport.errors | ForEach-Object { [string]$_.code + ' (' + [string]$_.path + ')' }) -join '; '
        if (-not $details) { $details = 'The fresh JVM engine self-test did not pass.' }
        $failure = New-Object InvalidOperationException('Runtime dependency preflight failed: ' + $details)
        $failure.Data['RuntimeDependencyReport'] = $dependencyReport
        throw $failure
    }
    return $dependencyReport
}
