#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Install', 'Start', 'Stop', 'Restart', 'Status', 'Uninstall')][string]$Action,
    [string]$BackendRoot,
    [string]$WebRoot,
    [string]$PythonExe,
    [string]$JavaExe,
    [string[]]$PublicUrl,
    [string]$RuntimeRoot = "$env:ProgramData\AlloyStudio",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$TaskName = 'AlloyStudioBackend',
    [ValidateRange(1, 32)][int]$Workers = 4,
    [ValidateRange(1, 30)][int]$EngineTimeout = 12,
    [switch]$EnableLuna
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-Windows
Import-Module ScheduledTasks -ErrorAction Stop
$RuntimeRoot = Get-LocalPath -Path $RuntimeRoot
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
    $BackendRoot = Get-LocalPath -Path $BackendRoot
    $WebRoot = Get-LocalPath -Path $WebRoot
    $PythonExe = Get-LocalPath -Path $PythonExe
    $JavaExe = Get-LocalPath -Path $JavaExe
    $scriptRoot = Get-LocalPath -Path $PSScriptRoot
    $publicRoots = @($WebRoot) + @(Get-IisPhysicalRoots)
    foreach ($private in @($BackendRoot, $RuntimeRoot, $scriptRoot)) {
        Assert-PrivatePath -Path $private -PublicRoots $publicRoots
    }
    foreach ($file in @($PythonExe, $JavaExe, (Join-Path $BackendRoot 'server.py'),
        (Join-Path $BackendRoot 'luna.py'), (Join-Path $BackendRoot 'exercises\catalogue.json'),
        (Join-Path $BackendRoot 'exercises\correct-pools.json'),
        (Join-Path $BackendRoot 'build\engine\classes\live\LiveFeedback.class'),
        (Join-Path $WebRoot 'web.config'))) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required deployment file is missing: $file" }
    }
    if (@(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue).Count) {
        throw 'Port 8080 is occupied. Stop the existing backend or choose a different host for this deployment.'
    }
    $origins = @($PublicUrl | ForEach-Object { Get-PublicOrigin -PublicUrl $_ } | Select-Object -Unique)
    & $PythonExe -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.10 or newer is required.' }
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
        workers = $Workers
        enable_luna = [bool]$EnableLuna
    }
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
        -Description 'Alloy Studio loopback backend. Private oracle catalogue; static files are served separately by IIS.' | Out-Null
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
    }
    'Uninstall' {
        Stop-BackendTask
        Unregister-ScheduledTask -TaskName $TaskName -TaskPath '\' -Confirm:$false
        Write-Output 'Backend task removed. Application files, configuration, logs, and private key are retained.'
    }
}
