#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$RuntimeRoot = "$env:ProgramData\AlloyStudio")
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-Windows
$RuntimeRoot = Get-LocalPath -Path $RuntimeRoot
$keyDirectory = Join-Path $RuntimeRoot 'secrets'
$keyPath = Join-Path $keyDirectory 'openai.key'
Assert-PrivatePath -Path $RuntimeRoot -PublicRoots @(Get-IisPhysicalRoots)
Get-LocalPath -Path $keyPath | Out-Null
New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
Set-RestrictedAcl -Path $RuntimeRoot
New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
Set-RestrictedAcl -Path $keyDirectory -Recurse

$secureKey = Read-Host 'OpenAI API key (input hidden)' -AsSecureString
$pointer = [IntPtr]::Zero
$plainKey = $null
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim()
    if (-not $plainKey.StartsWith('sk-') -or $plainKey -match '\s' -or $plainKey.Length -lt 20) {
        throw 'Key format is invalid. The existing key was not replaced.'
    }
    [IO.File]::WriteAllText($keyPath, $plainKey, (New-Object Text.UTF8Encoding($false)))
    Set-RestrictedAcl -Path $keyPath
    Write-Output 'Private key installed with Administrators/SYSTEM full control and LOCAL SERVICE read access.'
} finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $plainKey = $null
    $secureKey.Dispose()
}
