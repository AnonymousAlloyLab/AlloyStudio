# Shared by the Windows administrator scripts; never dot-source untrusted copies.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Windows {
    if ($env:OS -ne 'Windows_NT') { throw 'This script requires Windows and IIS 10.0.' }
}

function Get-LocalPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path -match '["\r\n]') {
        throw 'Use an absolute local drive path without quotes or newlines.'
    }
    $result = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($result.Length -le 2) { throw 'A drive root cannot be used as an application directory.' }
    $ancestor = $result
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Deployment paths must not contain junctions or symbolic links.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $result
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
            throw 'Cannot assign private ACLs through a junction or symbolic link.'
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
        Get-LocalPath -Path ([Environment]::ExpandEnvironmentVariables([string]$_.Value))
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
