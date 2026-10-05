#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Install', 'Start', 'Stop', 'Restart', 'Status', 'UpdateDataPermissions', 'Uninstall')][string]$Action,
    [string]$BackendRoot,
    [string]$WebRoot,
    [string]$PythonExe,
    [string]$JavaExe,
    [string[]]$PublicUrl,
    [string]$RuntimeRoot = "$env:ProgramData\AlloyStudio",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$TaskName = 'AlloyStudioBackend',
    # Zero selects the resource profile's worker count; existing explicit values remain supported.
    [ValidateRange(0, 32)][int]$Workers = 0,
    [ValidateRange(1, 30)][int]$EngineTimeout = 12,
    [ValidateSet('constrained', 'standard')][string]$ResourceProfile = 'constrained',
    [ValidateRange(0, 30)][int]$StartupTimeout = 0,
    [ValidateSet('persistent', 'oneshot')][string]$EngineMode = 'persistent',
    [ValidateRange(0, 65535)][ValidateScript({ $_ -ne 8080 })][int]$ControlPort = 0,
    # Exact canonical proxy addresses whose X-Forwarded-For is honoured (default none).
    [string[]]$TrustedProxy = @(),
    # CIDR blocks allowed to reach /admin and /api/admin (default deny). Configure the
    # edge addresses in the web.config administration rule; see deploy/iis/README.md.
    [string[]]$AdminNetwork = @(),
    [switch]$EnableLuna
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-Windows
Import-Module ScheduledTasks -ErrorAction Stop
$RuntimeRoot = Get-LocalPath -Path $RuntimeRoot -Purpose 'RuntimeRoot'
$configPath = Join-Path $RuntimeRoot 'backend-task.json'
$existing = Get-ScheduledTask -TaskName $TaskName -TaskPath '\' -ErrorAction SilentlyContinue

function Invoke-InstalledRuntimeDependencyCheck {
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw 'Backend configuration is missing; reinstall the complete private distribution.'
    }
    $installed = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $installedTask = Get-ScheduledTask -TaskName $TaskName -TaskPath '\'
    if (@($installedTask.Actions).Count -ne 1) { throw 'The backend task must have exactly one Python action.' }
    return Invoke-RuntimeDependencyCheck -PythonExe ([string]$installedTask.Actions[0].Execute) `
        -BackendRoot ([string]$installed.backend_root) -JavaExe ([string]$installed.java_exe)
}

function Stop-BackendTask {
    Disable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
    Stop-ScheduledTask -TaskName $TaskName -TaskPath '\'
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)
        if ($listeners.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Port 8080 is still listening after task stop. Inspect its owner before restarting; no unrelated process was killed.'
}

function Start-BackendTask {
    param([switch]$RuntimeChecked)
    if (-not $RuntimeChecked) { Invoke-InstalledRuntimeDependencyCheck | Out-Null }
    Enable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
    Start-ScheduledTask -TaskName $TaskName -TaskPath '\'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 3
            $task = Get-ScheduledTask -TaskName $TaskName -TaskPath '\'
            if ($health.status -eq 'ok' -and $task.State -eq 'Running') {
                Write-Output 'Alloy Studio backend task is running on 127.0.0.1:8080.'
                return
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Backend did not become ready in 60 seconds. Inspect Status and the protected backend.log.'
}

if ($Action -eq 'Install') {
    if ($existing) { throw 'Task already exists. Stop and Uninstall it before installing an updated task configuration.' }
    foreach ($required in @('BackendRoot', 'WebRoot', 'PythonExe', 'JavaExe', 'PublicUrl')) {
        if (-not (Get-Variable -Name $required -ValueOnly)) { throw "Install requires -$required." }
    }
    $BackendRoot = Get-LocalPath -Path $BackendRoot -Purpose 'BackendRoot'
    $WebRoot = Get-LocalPath -Path $WebRoot -Purpose 'WebRoot'
    $PythonExe = Get-ResolvedLocalPath -Path $PythonExe -Purpose 'PythonExe' -PathType Leaf
    $JavaExe = Get-ResolvedLocalPath -Path $JavaExe -Purpose 'JavaExe' -PathType Leaf
    $scriptRoot = Get-LocalPath -Path $PSScriptRoot -Purpose 'Administrator script directory'
    $publicRoots = @($WebRoot) + @(Get-IisPhysicalRoots)
    foreach ($private in @($BackendRoot, $RuntimeRoot, $scriptRoot)) {
        Assert-PrivatePath -Path $private -PublicRoots $publicRoots
    }
    foreach ($file in @($PythonExe, $JavaExe, (Join-Path $BackendRoot 'server.py'),
        (Join-Path $BackendRoot 'luna.py'), (Join-Path $BackendRoot 'exercises\exercises.sqlite3'),
        (Join-Path $BackendRoot 'exercise_store.py'), (Join-Path $BackendRoot 'exercise_sql.py'),
        (Join-Path $BackendRoot 'admin_auth.py'), (Join-Path $BackendRoot 'admin_upload.py'),
        (Join-Path $BackendRoot 'admin_luna.py'), (Join-Path $BackendRoot 'admin_service.py'),
        (Join-Path $BackendRoot 'traffic_identity.py'), (Join-Path $BackendRoot 'portal_routes.py'),
        (Join-Path $BackendRoot 'execution_profile.py'),
        (Join-Path $BackendRoot 'web\admin\index.html'), (Join-Path $BackendRoot 'web\admin\app.js'),
        (Join-Path $BackendRoot 'web\admin\styles.css'),
        (Join-Path $BackendRoot 'sql\schema.json'), (Join-Path $BackendRoot 'sql\queries.json'),
        (Join-Path $BackendRoot 'sql\compiled-queries.json'),
        (Join-Path $BackendRoot 'vendor\sqlean\provenance.json'),
        (Join-Path $BackendRoot 'build\engine\classes\live\LiveFeedback.class'),
        (Join-Path $BackendRoot 'build\engine\classes\live\UploadInspector.class'),
        (Join-Path $WebRoot 'web.config'))) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required deployment file is missing: $file" }
    }
    if (@(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue).Count) {
        throw 'Port 8080 is occupied. Stop the existing backend or choose a different host for this deployment.'
    }
    if ($ControlPort -and @(Get-NetTCPConnection -State Listen -LocalPort $ControlPort -ErrorAction SilentlyContinue).Count) {
        throw 'The requested private control port is occupied; choose another unused port.'
    }
    $origins = @($PublicUrl | ForEach-Object { Get-PublicOrigin -PublicUrl $_ } | Select-Object -Unique)
    & $PythonExe -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 or newer is required.' }
    $networkPolicy = @{ trusted_proxies = @($TrustedProxy); admin_networks = @($AdminNetwork) } | ConvertTo-Json -Compress
    $networkValidator = 'import json,sys; sys.dont_write_bytecode=True; sys.path.insert(0,sys.argv[1]); from traffic_identity import trusted_proxies,AdminNetworkPolicy; p=json.load(sys.stdin); trusted_proxies(p["trusted_proxies"]); AdminNetworkPolicy(p["admin_networks"])'
    $networkPolicy | & $PythonExe -I -c $networkValidator $BackendRoot
    if ($LASTEXITCODE -ne 0) { throw 'Invalid trusted proxy address or administration network; no task configuration was installed.' }
    # --version writes to stdout; -version uses stderr in Windows PowerShell.
    $javaVersion = (& $JavaExe --version) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?im)^(?:openjdk|java)\s+(\d+)' -or
        [int]$Matches[1] -lt 17) { throw 'The configured Java runtime must be version 17 or newer.' }
    Invoke-RuntimeDependencyCheck -PythonExe $PythonExe -BackendRoot $BackendRoot -JavaExe $JavaExe | Out-Null
    foreach ($directory in @($RuntimeRoot, (Join-Path $RuntimeRoot 'logs'), (Join-Path $RuntimeRoot 'secrets'))) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        Set-RestrictedAcl -Path $directory
    }
    Set-RestrictedAcl -Path $BackendRoot -Recurse
    # Authenticated publication needs SQLite data/journal writes, never changes
    # to Python/Java code, administrator configuration or provider credentials.
    Set-RestrictedAcl -Path (Join-Path $BackendRoot 'exercises') -LocalServiceAccess Modify -Recurse
    Set-RestrictedAcl -Path $scriptRoot -Recurse
    Set-RestrictedAcl -Path (Join-Path $RuntimeRoot 'logs') -LocalServiceAccess Modify -Recurse
    Set-RestrictedAcl -Path (Join-Path $RuntimeRoot 'secrets') -Recurse
    $config = [ordered]@{
        backend_root = $BackendRoot
        java_exe = $JavaExe
        public_origins = $origins
        key_file = (Join-Path $RuntimeRoot 'secrets\openai.key')
        log_directory = (Join-Path $RuntimeRoot 'logs')
        engine_timeout = $EngineTimeout
        resource_profile = $ResourceProfile
        engine_mode = $EngineMode
        control_port = $ControlPort
        enable_luna = [bool]$EnableLuna
        trusted_proxies = @($TrustedProxy)
        admin_networks = @($AdminNetwork)
    }
    if ($Workers -gt 0) { $config.workers = $Workers }
    if ($StartupTimeout -gt 0) { $config.startup_timeout = $StartupTimeout }
    [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))
    Set-RestrictedAcl -Path $configPath
    $launcher = Join-Path $scriptRoot 'run_backend.py'
    $arguments = '-u -X utf8 "' + $launcher + '" --config "' + $configPath + '"'
    $taskAction = New-ScheduledTaskAction -Execute $PythonExe -Argument $arguments -WorkingDirectory $BackendRoot
    $principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-19' -LogonType ServiceAccount -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName $TaskName -TaskPath '\' -Action $taskAction -Principal $principal `
        -Trigger (New-ScheduledTaskTrigger -AtStartup) -Settings $settings `
        -Description 'Alloy Studio loopback backend. Private exercise database; static files are served separately by IIS.' | Out-Null
    Start-BackendTask -RuntimeChecked
    if ($EnableLuna -and -not (Test-Path -LiteralPath $config.key_file -PathType Leaf) -and
        -not (Test-Path -LiteralPath (Join-Path $BackendRoot 'openai.local.json') -PathType Leaf)) {
        Write-Output 'Luna is enabled but no credential file is installed. Configure backend\openai.local.json and restart, or use Set-OpenAIKey.ps1.'
    }
    return
}

if (-not $existing) { throw "Scheduled backend task '$TaskName' is not installed." }
switch ($Action) {
    'Start' { Start-BackendTask }
    'Stop' { Stop-BackendTask; Write-Output 'Backend task stopped and disabled until Start.' }
    'UpdateDataPermissions' {
        # Older installations deliberately made the whole backend read-only.
        # Upgrade only the private data ACL; preserve all file/config contents.
        if ($existing.State -eq 'Running' -or
            @(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue).Count) {
            throw 'Stop the backend and all database writers before updating data permissions.'
        }
        $permissionConfigPath = Get-LocalPath -Path $configPath -Purpose 'Installed backend configuration'
        $permissionConfig = Get-Content -LiteralPath $permissionConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $permissionBackend = Get-LocalPath -Path ([string]$permissionConfig.backend_root) -Purpose 'Installed BackendRoot'
        $exerciseDirectory = Get-LocalPath -Path (Join-Path $permissionBackend 'exercises') -Purpose 'Private exercise directory'
        $databasePath = Get-LocalPath -Path (Join-Path $exerciseDirectory 'exercises.sqlite3') -Purpose 'Private exercise database'
        $publicRoots = @(Get-IisPhysicalRoots)
        Assert-PrivatePath -Path $permissionBackend -PublicRoots $publicRoots
        Assert-PrivatePath -Path $exerciseDirectory -PublicRoots $publicRoots
        if (-not (Test-Path -LiteralPath $exerciseDirectory -PathType Container) -or
            -not (Test-Path -LiteralPath $databasePath -PathType Leaf)) {
            throw 'The installed private exercise database is missing.'
        }
        Set-RestrictedAcl -Path $exerciseDirectory -LocalServiceAccess Modify -Recurse
        Write-Output 'Private exercise data permissions updated. Database, code, credentials and task settings are retained.'
    }
    'Restart' {
        Invoke-InstalledRuntimeDependencyCheck | Out-Null
        Stop-BackendTask
        Start-BackendTask -RuntimeChecked
    }
    'Status' {
        Get-ScheduledTask -TaskName $TaskName -TaskPath '\' | Select-Object TaskName, State
        Get-ScheduledTaskInfo -TaskName $TaskName -TaskPath '\' | Select-Object LastRunTime, LastTaskResult, NextRunTime
        Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue |
            Select-Object LocalAddress, LocalPort, OwningProcess
        try { Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 3 }
        catch { Write-Output 'Backend health endpoint is unavailable.' }
        if (Test-Path -LiteralPath $configPath -PathType Leaf) {
            $statusConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
            [pscustomobject]@{
                ResourceProfile = $(if ($statusConfig.PSObject.Properties.Name -contains 'resource_profile') { $statusConfig.resource_profile } else { 'constrained' })
                FeedbackWorkers = $(if ($statusConfig.PSObject.Properties.Name -contains 'workers') { [Math]::Min(2, [int]$statusConfig.workers) } else { 'profile default' })
                StartupTimeout = $(if ($statusConfig.PSObject.Properties.Name -contains 'startup_timeout') { $statusConfig.startup_timeout } else { 'profile default' })
                EngineTimeout = $statusConfig.engine_timeout
            }
            if ($statusConfig.PSObject.Properties.Name -contains 'control_port' -and [int]$statusConfig.control_port -gt 0) {
                $diagnosticsUrl = 'http://127.0.0.1:' + [int]$statusConfig.control_port + '/api/diagnostics'
                try { Invoke-RestMethod -Uri $diagnosticsUrl -TimeoutSec 3 }
                catch { Write-Output 'Private backend diagnostics are unavailable.' }
            }
        }
    }
    'Uninstall' {
        Stop-BackendTask
        Unregister-ScheduledTask -TaskName $TaskName -TaskPath '\' -Confirm:$false
        Write-Output 'Backend task removed. Application files, configuration, logs, and private key are retained.'
    }
}
