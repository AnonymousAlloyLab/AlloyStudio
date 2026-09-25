#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9 _.-]+$')][string]$SiteName = 'AlloyStudio',
    [ValidatePattern('^[A-Za-z0-9 _.-]+$')][string]$PoolName = 'AlloyStudio',
    [Parameter(Mandatory = $true)][string]$PublicUrl,
    [string]$RuntimeRoot = "$env:ProgramData\AlloyStudio",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$TaskName = 'AlloyStudioBackend',
    [switch]$UseDefaultCredentials
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-Windows
Import-Module WebAdministration -ErrorAction Stop
Import-Module ScheduledTasks -ErrorAction Stop

# Complete read-only validation before changing the state of an existing install.
$origin = Get-PublicOrigin -PublicUrl $PublicUrl
$applicationPath = ([Uri]$PublicUrl).AbsolutePath.TrimEnd('/')
$baseUrl = $PublicUrl.TrimEnd('/') + '/'
$RuntimeRoot = Get-LocalPath -Path $RuntimeRoot
$configPath = Get-LocalPath -Path (Join-Path $RuntimeRoot 'backend-task.json')
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'Backend configuration is missing. Run the documented Install step first.'
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($origin -notin $config.public_origins) {
    throw 'PublicUrl is not an installed public origin. Update the backend registration before starting.'
}

$sites = @(Get-Website | Where-Object { $_.Name -eq $SiteName })
if ($sites.Count -ne 1) { throw 'The specified IIS site must already exist.' }
$site = $sites[0]
if ($applicationPath) {
    $applications = @(Get-WebApplication -Site $SiteName | Where-Object { $_.Path -eq $applicationPath })
    if ($applications.Count -ne 1) { throw 'PublicUrl must identify an existing IIS application on the specified site.' }
    $application = $applications[0]
} else {
    $application = $site
}
if ([string]$application.applicationPool -ne $PoolName) {
    throw 'The specified app pool does not own this IIS application.'
}
if (-not (Test-Path -LiteralPath "IIS:\AppPools\$PoolName")) { throw 'The specified IIS app pool must already exist.' }

$WebRoot = Get-LocalPath -Path ([Environment]::ExpandEnvironmentVariables([string]$application.physicalPath))
if ([IO.Path]::GetFileName($WebRoot) -ne 'wwwroot') {
    throw 'The IIS application must point to the distribution public wwwroot directory.'
}
$expectedAssets = @('index.html', 'app.js', 'styles.css', 'web.config')
$assets = @(Get-ChildItem -LiteralPath $WebRoot -Force)
if (@(Compare-Object $expectedAssets @($assets.Name)).Count -or @($assets | Where-Object PSIsContainer).Count) {
    throw 'The public directory must contain only the four packaged public files.'
}
foreach ($asset in $assets) { Get-LocalPath -Path $asset.FullName | Out-Null }
if ((Get-FileHash -LiteralPath (Join-Path $WebRoot 'web.config') -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'web.config') -Algorithm SHA256).Hash) {
    throw 'The public web.config does not match this installed distribution.'
}
$publicRoots = @(Get-IisPhysicalRoots)
foreach ($privatePath in @($RuntimeRoot, $config.backend_root, $config.key_file, $config.log_directory, $PSScriptRoot)) {
    Assert-PrivatePath -Path (Get-LocalPath -Path $privatePath) -PublicRoots $publicRoots
}
if (-not (Test-Path -LiteralPath (Join-Path $config.backend_root 'server.py') -PathType Leaf)) {
    throw 'The configured private backend is missing.'
}

$task = Get-ScheduledTask -TaskName $TaskName -TaskPath '\' -ErrorAction Stop
$launcher = Join-Path (Get-LocalPath -Path $PSScriptRoot) 'run_backend.py'
$expectedArguments = '-u -X utf8 "' + $launcher + '" --config "' + $configPath + '"'
if (@($task.Actions).Count -ne 1 -or
    -not ([string]$task.Actions[0].Arguments).Equals($expectedArguments, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The specified backend task does not use this installed launcher and configuration.'
}
$principal = $task.Principal.UserId
if ($principal -notmatch '^S-1-') {
    $account = New-Object Security.Principal.NTAccount($principal)
    $principal = $account.Translate([Security.Principal.SecurityIdentifier]).Value
}
if ($principal -ne 'S-1-5-19') { throw 'The installed backend task must run as LOCAL SERVICE.' }
Invoke-RuntimeDependencyCheck -PythonExe ([string]$task.Actions[0].Execute) `
    -BackendRoot ([string]$config.backend_root) -JavaExe ([string]$config.java_exe) | Out-Null

# These are the two IIS prerequisite services; no service startup policy changes.
foreach ($serviceName in @('WAS', 'W3SVC')) {
    $service = Get-Service -Name $serviceName -ErrorAction Stop
    if ($service.Status -ne 'Running') {
        if ($service.Status -ne 'StartPending') { Start-Service -Name $serviceName -ErrorAction Stop }
        $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(30))
    }
}
if ((Get-WebAppPoolState -Name $PoolName).Value -ne 'Started') {
    Start-WebAppPool -Name $PoolName
}
& (Join-Path $PSScriptRoot 'Manage-AlloyStudio.ps1') -Action Start -RuntimeRoot $RuntimeRoot -TaskName $TaskName
if ((Get-Website -Name $SiteName).State -ne 'Started') {
    Start-Website -Name $SiteName
}

$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    try {
        $health = Invoke-RestMethod -Uri ($baseUrl + 'api/health') -TimeoutSec 5 -MaximumRedirection 0 `
            -UseDefaultCredentials:$UseDefaultCredentials
        if ($health.status -eq 'ok' -and $health.exercises -eq 181) {
            Write-Output "Alloy Studio is ready at $baseUrl (181 exercises)."
            return
        }
    } catch { }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
throw 'The installed backend started, but IIS public health did not pass within 60 seconds. Check site bindings, TLS, ARR, and authentication.'
