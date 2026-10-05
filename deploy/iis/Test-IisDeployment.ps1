#Requires -Version 5.1
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PublicUrl,
    [string]$RuntimeRoot = "$env:ProgramData\AlloyStudio",
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$TaskName = 'AlloyStudioBackend',
    [string]$ReportPath,
    [ValidateRange(130, 300)][int]$RequestTimeoutSeconds = 130,
    [switch]$ExpectAdminAccess,
    [switch]$CheckDiagnostics,
    [switch]$CheckLuna,
    [switch]$UseDefaultCredentials
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Assert-Windows
$origin = Get-PublicOrigin -PublicUrl $PublicUrl
$baseUrl = $PublicUrl.TrimEnd('/') + '/'
$RuntimeRoot = Get-LocalPath -Path $RuntimeRoot
if (-not $ReportPath) { $ReportPath = Join-Path $RuntimeRoot 'deployment-check.json' }
$ReportPath = Get-LocalPath -Path $ReportPath
$checks = New-Object 'Collections.Generic.List[object]'
$stage = 'initialization'
$failed = $false
$runtimeDependencies = $null

function Assert-Check {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw $Name }
    $checks.Add([ordered]@{name = $Name; status = 'PASS'})
}

function Invoke-PortalRequest {
    param([string]$RelativePath, [object]$Payload = $null, [string]$RequestOrigin = $origin)
    $request = [Net.HttpWebRequest]::Create($baseUrl + $RelativePath)
    $request.Timeout = $RequestTimeoutSeconds * 1000
    $request.ReadWriteTimeout = $RequestTimeoutSeconds * 1000
    $request.AllowAutoRedirect = $false
    $request.UseDefaultCredentials = [bool]$UseDefaultCredentials
    if ($null -ne $Payload) {
        $request.Method = 'POST'
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Headers['Origin'] = $RequestOrigin
        $bytes = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 8 -Compress))
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    try { $response = $request.GetResponse() }
    catch [Net.WebException] {
        if (-not $_.Exception.Response) { throw 'HTTP connection, TLS, or proxy request failed.' }
        $response = $_.Exception.Response
    }
    try {
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return @{Status = [int]$response.StatusCode; ContentType = $response.ContentType; Body = $content;
            CacheControl = [string]$response.Headers['Cache-Control'];
            AllowOrigin = [string]$response.Headers['Access-Control-Allow-Origin']}
    } finally { $response.Dispose() }
}

function Assert-PrivateAcl {
    param([string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    foreach ($rule in $acl.Access) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($rule.AccessControlType -eq 'Allow' -and $sid -notin @('S-1-5-18', 'S-1-5-19', 'S-1-5-32-544')) {
            throw 'Private ACL permits an unexpected account.'
        }
    }
}

function Assert-BackendWriteScope {
    param([string]$Path, [switch]$DatabaseWrite)
    Assert-PrivateAcl -Path $Path
    $rights = [Security.AccessControl.FileSystemRights]0
    foreach ($rule in (Get-Acl -LiteralPath $Path).Access) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($sid -ne 'S-1-5-19') { continue }
        if ($rule.AccessControlType -ne 'Allow') { throw 'Unexpected backend deny ACL; inspect effective permissions.' }
        $rights = $rights -bor $rule.FileSystemRights
    }
    $administrative = [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    if ($rights -band $administrative) { throw 'Backend must not change ACLs or own deployment files.' }
    if ($DatabaseWrite) {
        $modify = [Security.AccessControl.FileSystemRights]::Modify
        if (($rights -band $modify) -ne $modify) { throw 'Backend requires Modify access on private SQLite data only.' }
    } else {
        $write = [Security.AccessControl.FileSystemRights]::Write -bor
            [Security.AccessControl.FileSystemRights]::Delete -bor
            [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles
        if ($rights -band $write) { throw 'Backend has write access outside the allowed SQLite directory.' }
    }
}

try {
    $stage = 'complete bundled runtime dependencies and fresh JVM self-test'
    Import-Module ScheduledTasks
    $task = Get-ScheduledTask -TaskName $TaskName -TaskPath '\'
    $configPath = Join-Path $RuntimeRoot 'backend-task.json'
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (@($task.Actions).Count -ne 1) { throw 'The backend task must have exactly one Python action.' }
    $runtimeDependencies = Invoke-RuntimeDependencyCheck -PythonExe ([string]$task.Actions[0].Execute) `
        -BackendRoot ([string]$config.backend_root) -JavaExe ([string]$config.java_exe)
    Assert-Check ($runtimeDependencies.dependencies.Count -eq 7) 'all seven bundled JARs have their recorded SHA-256 hashes'
    Assert-Check ($runtimeDependencies.engine.status -eq 'PASS' -and $runtimeDependencies.engine.checks -eq 378) 'fresh JVM passes 378 engine checks with the packaged classpath'

    $stage = 'task identity, configuration, and isolation'
    $publicRoots = @(Get-IisPhysicalRoots)
    Assert-PrivatePath -Path $RuntimeRoot -PublicRoots $publicRoots
    Assert-PrivatePath -Path (Get-LocalPath $config.backend_root) -PublicRoots $publicRoots
    Assert-PrivatePath -Path (Get-LocalPath $config.key_file) -PublicRoots $publicRoots
    foreach ($privatePath in @($RuntimeRoot, $configPath, $config.backend_root, (Split-Path $config.key_file))) {
        Assert-PrivateAcl -Path $privatePath
    }
    if (Test-Path -LiteralPath $config.key_file) { Assert-PrivateAcl -Path $config.key_file }
    $localOpenAIConfig = Join-Path $config.backend_root 'openai.local.json'
    if (Test-Path -LiteralPath $localOpenAIConfig) {
        Assert-PrivatePath -Path (Get-LocalPath $localOpenAIConfig) -PublicRoots $publicRoots
        Assert-PrivateAcl -Path $localOpenAIConfig
    }
    foreach ($name in @('server.py', 'admin_auth.py', 'admin_upload.py', 'admin_luna.py', 'admin_service.py')) {
        Assert-BackendWriteScope -Path (Join-Path $config.backend_root $name)
    }
    Assert-BackendWriteScope -Path $config.backend_root
    foreach ($name in @('admin.local.json', 'openai.local.json')) {
        $privateConfig = Join-Path $config.backend_root $name
        if (Test-Path -LiteralPath $privateConfig) {
            Assert-PrivatePath -Path (Get-LocalPath $privateConfig) -PublicRoots $publicRoots
            Assert-BackendWriteScope -Path $privateConfig
        }
    }
    Assert-BackendWriteScope -Path (Join-Path $config.backend_root 'exercises') -DatabaseWrite
    Assert-BackendWriteScope -Path (Join-Path $config.backend_root 'exercises\exercises.sqlite3') -DatabaseWrite
    Assert-Check $true 'private SQLite data are writable; backend code and configuration remain read-only'
    $principal = $task.Principal.UserId
    if ($principal -notmatch '^S-1-') {
        $account = New-Object Security.Principal.NTAccount($principal)
        $principal = $account.Translate([Security.Principal.SecurityIdentifier]).Value
    }
    Assert-Check ($principal -eq 'S-1-5-19') 'scheduled task uses LOCAL SERVICE'
    Assert-Check ($task.State -eq 'Running') 'backend task is running'
    Assert-Check ($task.Settings.ExecutionTimeLimit -eq 'PT0S' -and $task.Settings.RestartCount -ge 1) 'task has unlimited runtime and failure restart'
    Assert-Check ($origin -in $config.public_origins) 'public origin is explicitly configured'
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8080)
    Assert-Check ($listeners.Count -gt 0 -and @($listeners | Where-Object LocalAddress -ne '127.0.0.1').Count -eq 0) 'backend binds only IPv4 loopback'
    Assert-Check $true 'private paths are outside IIS and ACLs exclude public readers'

    $stage = 'IIS static assets and proxy health'
    Import-Module WebAdministration
    $proxy = Get-WebConfiguration -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy'
    Assert-Check ([bool]$proxy.enabled -and ([TimeSpan]$proxy.timeout).TotalSeconds -ge 120) 'ARR proxy is enabled with a timeout of at least 120 seconds'
    foreach ($asset in @('', 'index.html', 'app.js', 'instance-graph.js', 'styles.css', 'dashboard/', 'dashboard/app.js', 'dashboard/styles.css', 'dashboard/data.json',
        'dashboard/index.html')) {
        $response = Invoke-PortalRequest $asset
        Assert-Check ($response.Status -eq 200 -and $response.Body.Length -gt 0) "public asset loads: $asset"
    }
    foreach ($asset in @('admin/', 'admin/app.js', 'admin/styles.css')) {
        $response = Invoke-PortalRequest $asset
        if ($ExpectAdminAccess) {
            Assert-Check ($response.Status -eq 200 -and $response.Body.Length -gt 0) "authorized-network administration asset loads: $asset"
        } else {
            Assert-Check ($response.Status -eq 404) "default-deny administration asset is unavailable: $asset"
        }
    }
    $response = Invoke-PortalRequest 'api/health'
    $health = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $health.status -eq 'ok' -and $health.exercises -gt 0) 'IIS proxy reaches the exercise database'
    $response = Invoke-PortalRequest 'api/channel' @{}
    $channel = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $channel.status -eq 'ok' -and
        $channel.channel -cmatch '^[A-Za-z0-9_-]{43}$') 'editing channel resolves a concrete admitted client identity through IIS'

    $stage = 'entire catalogue public projection'
    $listing = (Invoke-PortalRequest 'api/exercises').Body | ConvertFrom-Json
    Assert-Check ($listing.exercises.Count -eq $health.exercises) 'exercise listing matches backend health'
    $expected = @('id', 'title', 'group', 'predicate', 'description', 'environmentBefore', 'environmentAfter', 'predicateHeader', 'starter', 'source')
    foreach ($exercise in $listing.exercises) {
        $response = Invoke-PortalRequest ('api/exercises/' + [Uri]::EscapeDataString($exercise.id))
        if ($response.Status -ne 200) { throw 'An exercise failed to load.' }
        $record = $response.Body | ConvertFrom-Json
        if (@(Compare-Object $expected @($record.PSObject.Properties.Name)).Count) { throw 'Exercise field projection changed.' }
    }
    Assert-Check $true 'all exercise records contain only approved public fields'

    $stage = 'real canonical repair with UTF-8 input'
    $payload = @{exerciseId = 'graphs-inv5'; body = ('some (iden & adj) // caf' + [char]0x00E9); revision = 1}
    $response = Invoke-PortalRequest 'api/feedback' $payload
    $before = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $before.status -eq 'ok' -and $before.distance -eq 1) 'UTF-8 learner predicate returns distance one'
    Assert-Check ($before.comparison.strategy -eq 'nearest-known-correct' -and
        $before.comparison.complete -eq $true -and $before.comparison.poolSize -gt 1 -and
        $before.comparison.evaluatedCandidates -eq $before.comparison.poolSize) 'all candidates in the correct pool are compared'
    $operators = @($before.operations | Where-Object { $_.PSObject.Properties.Name -contains 'replacementOperator' })
    Assert-Check ($operators.Count -gt 0 -and $operators[0].replacementOperator -eq 'no') 'repair trace supplies an actionable replacement operator'
    Assert-Check (($before.operations | Measure-Object cost -Sum).Sum -eq $before.distance) 'operation costs sum to distance'
    Assert-Check ($response.Body -notmatch '"(?:oracleBody|oracleSource|originalSource|targetTerm|targetExpression)"\s*:') 'repair trace excludes private target fields'
    $payload.body = $operators[0].replacementOperator + ' (iden & adj)'
    $payload.revision = 2
    $response = Invoke-PortalRequest 'api/feedback' $payload
    $after = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $after.status -eq 'ok' -and $after.distance -eq 0) 'applying the shown operator reduces distance one to zero'

    $stage = 'raw AST feedback and behavioral examples'
    $payload.body = 'some (iden & adj)'
    $payload.revision = 3
    $payload.metric = 'ast'
    $response = Invoke-PortalRequest 'api/feedback' $payload
    $ast = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $ast.status -eq 'ok' -and
        $ast.metric -eq 'acgn-raw-ast-zhang-shasha-distance' -and $ast.distance -gt 0 -and
        $ast.operations.Count -gt 0 -and $ast.comparison.complete -eq $true) 'raw AST Zhang-Shasha feedback survives IIS'
    Assert-Check (($ast.operations | Measure-Object cost -Sum).Sum -eq $ast.distance) 'raw AST operation costs sum to distance'
    $response = Invoke-PortalRequest 'api/behavior' $payload
    $behavior = $response.Body | ConvertFrom-Json
    Assert-Check ($response.Status -eq 200 -and $behavior.status -eq 'ok' -and
        $behavior.metric -eq 'acgn-reward' -and $behavior.scope.moduleFacts -eq $true -and
        $behavior.categories.Count -eq 4) 'fact-constrained behavioral score and four categories survive IIS'
    Assert-Check (@(Compare-Object @('both', 'undercoverage', 'overcoverage', 'neither') @($behavior.categories.id)).Count -eq 0) 'all four behavioral categories are present'
    foreach ($category in $behavior.categories) {
        Assert-Check (@($category.instances).Count -le 3) ("at most three rendering inputs: " + $category.id)
        foreach ($instance in $category.instances) {
            Assert-Check (@($instance.states).Count -gt 0) 'instance includes concrete graph-rendering state'
        }
    }
    if ($CheckDiagnostics) {
        $stage = 'private control diagnostics and persistent reuse'
        Assert-Check ($config.PSObject.Properties.Name -contains 'control_port' -and [int]$config.control_port -gt 0) 'private control port is configured for acceptance'
        $diagnosticsUrl = 'http://127.0.0.1:' + [int]$config.control_port + '/api/diagnostics'
        $diagnostics = Invoke-RestMethod -Uri $diagnosticsUrl -TimeoutSec 5
        Assert-Check ($diagnostics.lanes.feedback.ready -ge 1 -and $diagnostics.lanes.behavior.ready -eq 1) 'feedback and behavioral workers remain ready after requests'
        $backendProcess = @(Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort 8080)[0].OwningProcess
        $engineProcesses = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = " + $backendProcess + " AND Name = 'java.exe'") | Select-Object -ExpandProperty ProcessId | Sort-Object)
        Assert-Check ($engineProcesses.Count -ge 2) 'persistent engine children belong to the scheduled backend'
        $feedbackCredits = $diagnostics.lanes.feedback.launchCredits
        $behaviorCredits = $diagnostics.lanes.behavior.launchCredits
        # Different source bytes force fresh analysis rather than merely a cache hit.
        $payload.body = 'some (iden & adj) // persistent reuse acceptance'
        $payload.metric = 'canonical'
        $payload.revision = 4
        $response = Invoke-PortalRequest 'api/feedback' $payload
        Assert-Check ($response.Status -eq 200 -and ($response.Body | ConvertFrom-Json).status -eq 'ok') 'second fresh canonical request succeeds'
        $response = Invoke-PortalRequest 'api/behavior' $payload
        Assert-Check ($response.Status -eq 200 -and ($response.Body | ConvertFrom-Json).status -eq 'ok') 'second fresh behavioral request succeeds'
        $diagnostics = Invoke-RestMethod -Uri $diagnosticsUrl -TimeoutSec 5
        $afterProcesses = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = " + $backendProcess + " AND Name = 'java.exe'") | Select-Object -ExpandProperty ProcessId | Sort-Object)
        Assert-Check (@(Compare-Object $engineProcesses $afterProcesses).Count -eq 0) 'fresh feedback and behavior preserve actual JVM process identities'
        Assert-Check ($diagnostics.lanes.feedback.launchCredits -ge $feedbackCredits -and
            $diagnostics.lanes.behavior.launchCredits -ge $behaviorCredits -and
            $diagnostics.lanes.feedback.unreaped -eq 0 -and $diagnostics.lanes.behavior.unreaped -eq 0) 'fresh requests reuse workers without consuming further launch credits'
        $response = Invoke-PortalRequest 'api/diagnostics'
        Assert-Check ($response.Status -eq 404) 'private diagnostics are not reachable through IIS'
    }

    $stage = 'request boundaries and private routes'
    $anonymousAdmin = Invoke-PortalRequest 'api/admin/prepare' @{}
    Assert-Check ($anonymousAdmin.Status -in @(401, 403, 404, 503)) 'anonymous administration is rejected'
    if ($ExpectAdminAccess) {
        Assert-Check ($anonymousAdmin.CacheControl -match 'no-store' -and $anonymousAdmin.CacheControl -match 'private' -and
            -not $anonymousAdmin.AllowOrigin) 'backend admin rejection cannot be cached or read through CORS'
    } else {
        Assert-Check ($anonymousAdmin.Status -eq 404 -and -not $anonymousAdmin.AllowOrigin) 'default-deny administration API remains unavailable'
    }
    $response = Invoke-PortalRequest 'api/feedback' $payload 'https://attacker.invalid'
    Assert-Check ($response.Status -eq 403 -and $response.ContentType -match 'application/json') 'cross-origin rejection survives IIS as JSON'
    $payload.revision = $true
    $response = Invoke-PortalRequest 'api/feedback' $payload
    Assert-Check ($response.Status -eq 400 -and $response.ContentType -match 'application/json') 'invalid request rejection survives IIS as JSON'
    foreach ($privateRoute in @('server.py', 'luna.py', 'web.config', 'exercises/catalogue.json',
        'backend/exercises/catalogue.json', 'exercises/correct-pools.json', 'backend/exercises/correct-pools.json',
        'exercises/exercises.sqlite3', 'backend/exercises/exercises.sqlite3',
        'exercises/exercises.sqlite3-wal', 'exercises/exercises.sqlite3-shm', 'exercises/exercises.sqlite3-journal',
        'exercise_store.py', 'exercise_sql.py', 'sql/schema.json', 'sql/queries.json',
        'sql/compiled-queries.json', 'vendor/sqlean/provenance.json', 'scripts/manage_exercises.py',
        'admin.local.json', 'backend/admin.local.json', 'admin/admin.local.json',
        'admin_auth.py', 'admin_upload.py', 'admin_luna.py', 'admin_service.py', 'scripts/configure_admin.py',
        'admin/upload.als', 'admin/source.als', 'admin/.admin-config-fixture.tmp',
        'vendor/acgn/lib/alloy.jar', 'openai.key', 'openai.local.json', 'backend/openai.local.json', '.env',
        'deploy/iis/run_backend.py', 'backend-task.json', 'api/exercises/../../catalogue.json')) {
        $response = Invoke-PortalRequest $privateRoute
        Assert-Check ($response.Status -in @(400, 403, 404)) "private route unavailable: $privateRoute"
    }

    if ($CheckLuna) {
        $stage = 'live Luna explanation'
        $payload.body = 'some (iden & adj)'
        $payload.revision = 3
        $response = Invoke-PortalRequest 'api/explain' $payload
        $explanation = $response.Body | ConvertFrom-Json
        Assert-Check ($response.Status -eq 200 -and $explanation.status -eq 'ok' -and $explanation.model -eq 'gpt-6-luna') 'configured Luna returns a live explanation'
    }
} catch {
    $failed = $true
    if ($_.Exception.Data.Contains('RuntimeDependencyReport')) {
        $runtimeDependencies = $_.Exception.Data['RuntimeDependencyReport']
    }
    # Exception values and HTTP bodies are deliberately absent from the report.
    $checks.Add([ordered]@{name = $stage; status = 'FAIL'; detail = 'Deployment check failed; inspect this stage on the Windows host.'})
} finally {
    if ($null -ne $runtimeDependencies) {
        foreach ($dependency in $runtimeDependencies.dependencies) {
            $checks.Add([ordered]@{name = ('bundled dependency: ' + $dependency.name); status = $dependency.status;
                sha256 = $dependency.sha256; expected_sha256 = $dependency.expectedSha256})
        }
    }
    $report = [ordered]@{
        generated_at = [DateTime]::UtcNow.ToString('o')
        status = $(if ($failed) { 'FAIL' } else { 'PASS' })
        environment = 'Windows IIS 10 deployment acceptance; not a universal correctness claim'
        public_url = $baseUrl
        live_luna_requested = [bool]$CheckLuna
        administration_network_access_expected = [bool]$ExpectAdminAccess
        private_diagnostics_requested = [bool]$CheckDiagnostics
        runtime_dependencies = $runtimeDependencies
        checks = @($checks.ToArray())
    }
    [IO.File]::WriteAllText($ReportPath, ($report | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    Write-Output ("Deployment acceptance: " + $report.status + "; report: " + $ReportPath)
}
if ($failed) { exit 1 }
