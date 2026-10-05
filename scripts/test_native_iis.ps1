#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param()
# This provisions a disposable CI machine. It is deliberately not an installer
# for an existing user's IIS host, which may have unrelated production sites.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Native IIS provisioning is restricted to a disposable Windows Actions runner.'
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $root 'build\native-iis'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$report = [ordered]@{status = 'FAIL'; environment = 'native Windows Server 2022 IIS 10';
    revision = $env:GITHUB_SHA; stage = 'initialization'; checks = @();
    live_provider_calls = $false; deployment_archive_uploaded = $false}
$site = 'AlloyStudioNativeCI'
$pool = 'AlloyStudioNativeCI'
$sentinelSite = 'AlloyStudioSentinelCI'
$task = 'AlloyStudioNativeCI'
$runtime = Join-Path $output 'runtime'
$publicUrl = 'http://127.0.0.1:8099/'
$python = $null
$java = $null
$installed = $false
$manager = $null

function Assert-CI {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { $script:report.failed_check = $Name; throw $Name }
    $script:report.checks += [ordered]@{name = $Name; status = 'PASS'}
}

function Install-VerifiedMsi {
    param([string]$Name, [string]$Url, [string]$Sha256)
    $destination = Join-Path $output $Name
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $destination -TimeoutSec 120
    Assert-CI ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $Sha256) ("Microsoft installer SHA-256: " + $Name)
    $signature = Get-AuthenticodeSignature -LiteralPath $destination
    Assert-CI ($signature.Status -eq 'Valid' -and $signature.SignerCertificate.Subject -match 'O=Microsoft Corporation(?:,|$)') ("Microsoft installer signature: " + $Name)
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" `
        -ArgumentList @('/i', ('"' + $destination + '"'), '/qn', '/norestart') -Wait -PassThru
    Assert-CI ($process.ExitCode -eq 0) ("IIS extension install exit " + $process.ExitCode + ': ' + $Name)
}

try {
    $report.stage = 'selected runtime invocation'
    # Application lookup can expose more than one PATH candidate. The call
    # operator requires one executable path, never an array of Source values.
    $pythonCandidates = @(Get-Command python.exe -CommandType Application)
    $javaCandidates = @(Get-Command java.exe -CommandType Application)
    $report.python_candidate_count = $pythonCandidates.Count
    $report.java_candidate_count = $javaCandidates.Count
    $python = [string]$pythonCandidates[0].Path
    $java = [string]$javaCandidates[0].Path
    Assert-CI (Test-Path -LiteralPath $python -PathType Leaf) 'selected Python executable exists'
    Assert-CI (Test-Path -LiteralPath $java -PathType Leaf) 'selected Java executable exists'
    & $python -I -c 'import sys; sys.exit(0)'
    Assert-CI ($LASTEXITCODE -eq 0) 'selected Python executable can run before provisioning'
    $report.stage = 'IIS prerequisites'
    $feature = Install-WindowsFeature Web-Server, Web-Static-Content, Web-Default-Doc, Web-Http-Errors, Web-Filtering, Web-Scripting-Tools -IncludeManagementTools
    Assert-CI ($feature.Success -and [string]$feature.RestartNeeded -eq 'No') 'IIS features installed without pending restart'
    # Sources: Microsoft's URL Rewrite 2.1 and ARR 3.0 download pages, documented
    # in deploy/iis/README.md. Both payload digests are pinned; never run a changed MSI.
    Install-VerifiedMsi 'rewrite_amd64_en-US.msi' 'https://download.microsoft.com/download/1/2/8/128E2E22-C1B9-44A4-BE2A-5859ED1D4592/rewrite_amd64_en-US.msi' '37342FF2F585F263F34F48E9DE59EB1051D61015A8E967DBDE4075716230A32A'
    Install-VerifiedMsi 'requestRouter_amd64.msi' 'https://download.microsoft.com/download/e/9/8/e9849d6a-020e-47e4-9fd0-a023e99b54eb/requestRouter_amd64.msi' 'FB61FDB7101795A34D5129CB37EEE43AB675C7ED76BA3A3B23B039D8C90C2A4B'
    Import-Module WebAdministration
    $appcmd = "$env:windir\System32\inetsrv\appcmd.exe"
    & $appcmd set config -section:system.webServer/proxy /enabled:true /timeout:00:02:00 /includePortInXForwardedFor:false /commit:apphost | Out-Null
    Assert-CI ($LASTEXITCODE -eq 0) 'disposable runner ARR configured with a 120-second budget'

    $report.stage = 'private package extraction and separate sentinel site'
    $archive = Join-Path $output 'native-iis-private.zip'
    & $python -c 'from pathlib import Path; import sys; from scripts.package_iis import build_package; build_package(Path.cwd(), Path(sys.argv[1]))' $archive
    Assert-CI ($LASTEXITCODE -eq 0) 'freshly built classes packaged into private local archive'
    $unpack = Join-Path $output 'distribution'
    Expand-Archive -LiteralPath $archive -DestinationPath $unpack
    $distribution = $unpack
    $web = Join-Path $distribution 'wwwroot'
    $backend = Join-Path $distribution 'backend'
    $manager = Join-Path $distribution 'deploy\iis\Manage-AlloyStudio.ps1'
    Assert-CI (Test-Path -LiteralPath $manager -PathType Leaf) 'complete extracted installer exists'
    foreach ($name in @($site, $sentinelSite)) {
        Assert-CI (@(Get-Website | Where-Object Name -eq $name).Count -eq 0) 'fixture site name is unoccupied'
    }
    Assert-CI (@(Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue).Count -eq 0) 'fixture task name is unoccupied'
    foreach ($port in @(8080, 8081, 8099, 8100)) {
        Assert-CI (@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -eq 0) 'fixture listener port is unoccupied'
    }
    $sentinel = Join-Path $output 'sentinel'
    New-Item -ItemType Directory -Path $sentinel | Out-Null
    [IO.File]::WriteAllText((Join-Path $sentinel 'index.html'), 'alloy-native-iis-sentinel')
    New-WebAppPool -Name $pool | Out-Null
    Set-ItemProperty "IIS:\AppPools\$pool" -Name managedRuntimeVersion -Value ''
    New-Website -Name $sentinelSite -PhysicalPath $sentinel -Port 8100 -IPAddress '127.0.0.1' | Out-Null
    New-Website -Name $site -PhysicalPath $web -Port 8099 -IPAddress '127.0.0.1' -ApplicationPool $pool | Out-Null
    $sentinelBefore = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8100/index.html' -TimeoutSec 10).Content
    $sentinelBinding = [string](Get-Website -Name $sentinelSite).Bindings.Collection[0].bindingInformation

    $report.stage = 'real Local Service installation and readiness'
    $installed = $true
    & $manager -Action Install -BackendRoot $backend -WebRoot $web -PythonExe $python -JavaExe $java `
        -PublicUrl $publicUrl -RuntimeRoot $runtime -TaskName $task -ResourceProfile constrained `
        -ControlPort 8081
    & (Join-Path $distribution 'deploy\iis\Start-AlloyStudio.ps1') -SiteName $site -PoolName $pool `
        -PublicUrl $publicUrl -RuntimeRoot $runtime -TaskName $task
    $configuration = Get-Content -LiteralPath (Join-Path $runtime 'backend-task.json') -Raw | ConvertFrom-Json
    Assert-CI ($configuration.resource_profile -eq 'constrained' -and
        $configuration.PSObject.Properties.Name -notcontains 'workers' -and
        $configuration.PSObject.Properties.Name -notcontains 'startup_timeout') 'profile defaults reach installed task without accidental overrides'
    $report.stage = 'native deployment acceptance'
    $acceptance = Join-Path $output 'deployment-check-private.json'
    # A child PowerShell contains the acceptance script's explicit exit code.
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $distribution 'deploy\iis\Test-IisDeployment.ps1') `
        -PublicUrl $publicUrl -RuntimeRoot $runtime -TaskName $task -ReportPath $acceptance -CheckDiagnostics
    $acceptanceExitCode = $LASTEXITCODE
    # Keep only fixed check names/statuses. No HTTP body, source, configuration,
    # database, backend log or private archive enters the published summary.
    if (Test-Path -LiteralPath $acceptance -PathType Leaf) {
        $accepted = Get-Content -LiteralPath $acceptance -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($accepted.PSObject.Properties.Name -contains 'last_http_status') {
            $report.acceptance_http_status = $accepted.last_http_status
        }
        foreach ($check in $accepted.checks) {
            $report.checks += [ordered]@{name = [string]$check.name; status = [string]$check.status}
        }
    }
    Assert-CI ($acceptanceExitCode -eq 0) 'native IIS deployment acceptance completed'
    Assert-CI ($accepted.status -eq 'PASS') 'native acceptance report passes'
    $report.stage = 'explicit administration network admission'
    # Synthetic loopback policy only: leave trusted_proxies empty. On this host
    # both ARR and the real test client are 127.0.0.1, so trusting that address
    # would leave no untrusted client hop and correctly reject the identity.
    # This does not test an external/Cloudflare forwarding chain.
    # First open the edge while the backend is
    # still default-deny, then admit loopback at the backend. No password/key is
    # installed and no model is published by this deployment smoke check.
    $publicConfig = Join-Path $web 'web.config'
    [xml]$xml = Get-Content -LiteralPath $publicConfig -Raw
    $conditions = $xml.SelectSingleNode("/configuration/system.webServer/rewrite/rules/rule[@name='Administration network policy']/conditions")
    $allow = $xml.CreateElement('add')
    $allow.SetAttribute('input', '{REMOTE_ADDR}')
    $allow.SetAttribute('pattern', '^127\.0\.0\.1$')
    $allow.SetAttribute('negate', 'true')
    $conditions.AppendChild($allow) | Out-Null
    $xml.Save($publicConfig)
    & $python -I (Join-Path $distribution 'deploy\iis\run_backend.py') --check-web-config $publicConfig `
        --template (Join-Path $distribution 'deploy\iis\web.config') | Out-Null
    Assert-CI ($LASTEXITCODE -eq 0) 'exact loopback edge admission passes packaged policy validator'
    try {
        $denied = Invoke-WebRequest -UseBasicParsing ($publicUrl + 'admin/') -TimeoutSec 10
        $deniedStatus = [int]$denied.StatusCode
    } catch [Net.WebException] {
        if (-not $_.Exception.Response) { throw }
        $deniedStatus = [int]$_.Exception.Response.StatusCode
        $_.Exception.Response.Dispose()
    }
    Assert-CI ($deniedStatus -eq 404) 'backend still denies administration after edge-only admission'
    $configuration.admin_networks = @('127.0.0.1/32')
    [IO.File]::WriteAllText((Join-Path $runtime 'backend-task.json'), ($configuration | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    $previousBackend = @(Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort 8080)[0].OwningProcess
    $previousChildren = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = " + $previousBackend + " AND Name = 'java.exe'") | Select-Object -ExpandProperty ProcessId)
    & $manager -Action Restart -RuntimeRoot $runtime -TaskName $task
    foreach ($child in $previousChildren) {
        Assert-CI ($null -eq (Get-Process -Id $child -ErrorAction SilentlyContinue)) 'task restart leaves no previous Java worker process'
    }
    foreach ($asset in @('admin/', 'admin/app.js', 'admin/styles.css')) {
        $response = Invoke-WebRequest -UseBasicParsing ($publicUrl + $asset) -TimeoutSec 10
        Assert-CI ($response.StatusCode -eq 200 -and $response.Content.Length -gt 0) ("both network policies admit administration asset: " + $asset)
    }
    $report.stage = 'multi-site isolation and shutdown'
    $backendProcess = @(Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort 8080)[0].OwningProcess
    $children = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = " + $backendProcess + " AND Name = 'java.exe'") | Select-Object -ExpandProperty ProcessId)
    & $manager -Action Stop -RuntimeRoot $runtime -TaskName $task
    foreach ($child in $children) {
        Assert-CI ($null -eq (Get-Process -Id $child -ErrorAction SilentlyContinue)) 'task stop leaves no previous Java worker process'
    }
    $sentinelAfter = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8100/index.html' -TimeoutSec 10).Content
    Assert-CI ($sentinelBefore -eq 'alloy-native-iis-sentinel' -and $sentinelAfter -eq $sentinelBefore -and
        (Get-Website -Name $sentinelSite).State -eq 'Started' -and
        [string](Get-Website -Name $sentinelSite).Bindings.Collection[0].bindingInformation -eq $sentinelBinding) 'another IIS site and binding survive Alloy installation and shutdown'
    Assert-CI (@(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue).Count -eq 0) 'scheduled backend releases its public listener'
    $report.status = 'PASS'
    $report.stage = 'complete'
} catch {
    # Stage names are fixed above; avoid emitting exception/model/credential text.
    $report.failure_type = $_.Exception.GetType().FullName
    # Fixed source coordinates identify the failed command without publishing
    # exception text, invocation arguments, HTTP responses, or runtime paths.
    $failureFile = [IO.Path]::GetFileName($_.InvocationInfo.ScriptName)
    if ($failureFile -in @('test_native_iis.ps1', 'Common.ps1', 'Manage-AlloyStudio.ps1', 'Start-AlloyStudio.ps1')) {
        $report.failure_script = $failureFile
        $report.failure_line = [int]$_.InvocationInfo.ScriptLineNumber
    }
    Write-Output ('Native IIS acceptance failed at stage: ' + $report.stage)
} finally {
    if ($installed -and $manager -and (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue)) {
        try { & $manager -Action Uninstall -RuntimeRoot $runtime -TaskName $task | Out-Null } catch { }
    }
    if (Get-Module WebAdministration) {
        foreach ($name in @($site, $sentinelSite)) {
            if (@(Get-Website | Where-Object Name -eq $name).Count) { Remove-Website -Name $name }
        }
        if (Test-Path "IIS:\AppPools\$pool") { Remove-WebAppPool -Name $pool }
    }
    $report.generated_at = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText((Join-Path $output 'public-summary.json'), ($report | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
}
if ($report.status -ne 'PASS') { exit 1 }
