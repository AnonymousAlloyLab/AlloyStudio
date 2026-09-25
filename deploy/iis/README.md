# Deploy Alloy Studio on IIS 10.0

IIS serves the three public assets and proxies this application's `api/*` requests
to Python at `127.0.0.1:8080`. Python runs the bundled Java canonical engine and
keeps the exercise catalogue and Luna credentials private. The backend runs as a
Windows **scheduled task**, under LOCAL SERVICE, independently of IIS app pool
recycling. Both a dedicated website and an application such as `/alloy/` work.

The package preserves all 181 exercises and their existing environments, plus
private correct-predicate pools including every exercise's oracle. Each request
compares all admitted candidates and traces one nearest match. The ZIP
contains private oracle material and is a server distribution; keep it out of a
public repository and never make the package root an IIS physical directory.
Only `wwwroot` is public. No API key is included in the distribution.

This deployment support was developed on Linux. Cross-platform tests and
PowerShell syntax checks do not establish that a particular Windows server is
configured correctly. Run the Windows acceptance script below and the lifecycle
checks on the actual target before recording deployment acceptance. No Windows
host was changed or claimed as verified during development.

## 1. Build and transfer the package

From a source checkout containing the private catalogue, build the Java engine
and create the distribution:

```bash
./scripts/build.sh
python3 scripts/package_iis.py
```

The Windows source-build equivalent is:

```powershell
powershell -NoProfile -File scripts/build.ps1 -RequireNode
python scripts/package_iis.py
```

This uses a JDK 17 or newer, Python 3.10 or newer, and Node 20 or newer for the
JavaScript syntax check. Optional `-JavaCompiler` and `-Python` arguments accept
absolute executable paths. The IIS runtime needs no Node, npm,
bash, compiler, pip packages, or original ACGN checkout. Use a machine-wide
64-bit Python installation and Java 17+ runtime readable by LOCAL SERVICE; avoid
the Microsoft Store Python alias or executables in a user's private profile.

Transfer `build/iis/alloy-studio-iis.zip` and its `.sha256` sidecar through a private
channel and compare the archive's SHA-256 with the sidecar after transfer. In an elevated
**64-bit Windows PowerShell 5.1** session on the Windows server:

```powershell
$ErrorActionPreference = 'Stop'
$Bundle = 'C:\Program Files\AlloyStudio'
Expand-Archive -LiteralPath 'C:\Staging\alloy-studio-iis.zip' -DestinationPath $Bundle
Get-ChildItem -LiteralPath "$Bundle\deploy\iis" -Filter '*.ps1' | Unblock-File
$WebRoot = "$Bundle\wwwroot"
$BackendRoot = "$Bundle\backend"
$RuntimeRoot = Join-Path $env:ProgramData 'AlloyStudio'
$Manage = "$Bundle\deploy\iis\Manage-AlloyStudio.ps1"
$PublicHost = 'alloy.example.org'
$PublicUrl = "https://$PublicHost/"
$PythonExe = 'C:\Program Files\Python313\python.exe' # replace with the installed path
$JavaExe = 'C:\Program Files\Java\jdk-17\bin\java.exe' # replace with the installed path
```

The extracted layout is:

```text
AlloyStudio\
  wwwroot\              index.html, app.js, styles.css, web.config ONLY
  backend\              Python code, classes, JARs, private catalogue and correct pools
  deploy\iis\           administrator scripts and the task launcher
  manifest.json         packaged file hashes
```

Use regular local directories. The management scripts reject junctions,
symbolic links, and private paths overlapping IIS physical directories. The
default task state is stored separately in `C:\ProgramData\AlloyStudio`.

## 2. Install IIS and enable the reverse proxy

On Windows Server with IIS 10.0, install the static site and management features:

```powershell
Install-WindowsFeature Web-Server, Web-Static-Content, Web-Default-Doc, Web-Http-Errors, Web-Filtering, Web-Mgmt-Console, Web-Scripting-Tools -IncludeManagementTools
```

If the result reports a required restart, restart Windows before continuing.
On a Windows client with IIS 10.0, enable the corresponding IIS features through
Windows Features; `Install-WindowsFeature` is a Windows Server command. These
commands follow Microsoft's [IIS role installation guidance](https://learn.microsoft.com/en-us/windows-server/networking/core-network-guide/cncg/server-certs/install-the-web-server-web1).

Install Microsoft's 64-bit [URL Rewrite 2.1](https://www.iis.net/downloads/microsoft/url-rewrite)
and [Application Request Routing 3.0](https://www.microsoft.com/en-us/download/details.aspx?id=47333)
using their signed installers. These are separate IIS extensions. ARR's proxy
mode is disabled by default; Microsoft's [reverse proxy walkthrough](https://learn.microsoft.com/en-us/iis/extensions/url-rewrite-module/reverse-proxy-with-url-rewrite-v2-and-application-request-routing)
describes the required modules and server proxy setting.

```powershell
Import-Module WebAdministration
$AppCmd = "$env:windir\System32\inetsrv\appcmd.exe"
& $AppCmd list modules
# Confirm RewriteModule and ApplicationRequestRouting are listed.
& $AppCmd set config -section:system.webServer/proxy /enabled:true /timeout:00:01:00 /commit:apphost
if ($LASTEXITCODE -ne 0) { throw 'ARR proxy configuration failed.' }
```

This changes ARR's **server-wide** proxy setting, which is shared with other IIS
sites. The 60-second timeout exceeds the default 12-second canonical calculation
plus the 20-second Luna request; retain at least 60 seconds. The included
`web.config` has only a fixed loopback upstream. Its second rule allows only the
three known public assets. No wildcard filesystem handler exposes the backend.
Application-relative rewrite matching also supports `/alloy/api/...`; see the
[URL Rewrite configuration reference](https://learn.microsoft.com/en-us/iis/extensions/url-rewrite-module/url-rewrite-module-configuration-reference).

## 3. Create the HTTPS website or IIS application

For a **new dedicated website**, use an existing trusted TLS certificate in the
Local Computer `My` certificate store whose names include `$PublicHost`. Configure
DNS to reach this server, then replace the certificate thumbprint below:

```powershell
$SiteName = 'AlloyStudio'
$PoolName = 'AlloyStudio'
$CertThumbprint = 'REPLACE_WITH_EXISTING_CERTIFICATE_THUMBPRINT'
if (-not (Test-Path -LiteralPath "Cert:\LocalMachine\My\$CertThumbprint")) { throw 'Install the website certificate first.' }
New-WebAppPool -Name $PoolName
Set-ItemProperty "IIS:\AppPools\$PoolName" -Name managedRuntimeVersion -Value ''
New-Website -Name $SiteName -PhysicalPath $WebRoot -ApplicationPool $PoolName `
    -Port 443 -HostHeader $PublicHost -Ssl -SslFlags 1
(Get-WebBinding -Name $SiteName -Protocol https -Port 443 -HostHeader $PublicHost).AddSslCertificate($CertThumbprint, 'My')
$IisLocation = $SiteName
```

The `-SslFlags 1` option creates an SNI binding. These calls use Microsoft's
[website cmdlet](https://learn.microsoft.com/en-us/powershell/module/webadministration/new-website?view=windowsserver2025-ps)
and [certificate binding example](https://learn.microsoft.com/en-us/powershell/module/webadministration/new-webbinding?view=windowsserver2025-ps).
Permit incoming HTTPS on port 443 under the host's firewall policy. Python binds
only to IPv4 loopback, so no inbound rule for port 8080 is needed.

Alternatively, for **`/alloy/` under an existing HTTPS site**, use this block
instead of creating the dedicated website. The parent site's DNS, certificate,
HTTPS binding, and firewall must already work:

```powershell
$SiteName = 'Existing Site'
$PoolName = 'AlloyStudio'
New-WebAppPool -Name $PoolName
Set-ItemProperty "IIS:\AppPools\$PoolName" -Name managedRuntimeVersion -Value ''
New-WebApplication -Site $SiteName -Name 'alloy' -PhysicalPath $WebRoot -ApplicationPool $PoolName
$IisLocation = "$SiteName/alloy"
$PublicUrl = "https://$PublicHost/alloy/"
```

This must be an IIS application, with the package's `wwwroot` as its physical
directory. Use the trailing slash in the application URL. HTML, styles, scripts,
API requests, and exercise links resolve within that application. Existing
server-wide rewrite or authentication policies can still affect child
applications; the acceptance test catches failures through the public URL.

For either option, let anonymous static requests use this application's pool
identity and give that identity read access **only to the public directory**:

```powershell
Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' -Location $IisLocation `
    -Filter 'system.webServer/security/authentication/anonymousAuthentication' -Name userName -Value ''
& icacls.exe $WebRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "IIS AppPool\${PoolName}:(OI)(CI)RX" /T
if ($LASTEXITCODE -ne 0) { throw 'Public directory ACL configuration failed.' }
```

If an existing site already requires authentication, retain the access policy
appropriate for its users and use `-UseDefaultCredentials` when running the
acceptance script with Windows authentication. IIS supplies access control;
the portal has no login or account system.

## 4. Install the private backend task and configure Luna

```powershell
& $Manage -Action Install -BackendRoot $BackendRoot -WebRoot $WebRoot `
    -PythonExe $PythonExe -JavaExe $JavaExe -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -EnableLuna
$LunaConfig = Join-Path $BackendRoot 'openai.local.json'
if (-not (Test-Path -LiteralPath $LunaConfig)) {
    Copy-Item -LiteralPath (Join-Path $BackendRoot 'openai.example.json') -Destination $LunaConfig
}
Start-Process -FilePath notepad.exe -ArgumentList ('"' + $LunaConfig + '"') -Wait
# Save and close the editor before restarting the backend.
& $Manage -Action Restart -RuntimeRoot $RuntimeRoot
```

In that private file, replace the empty `api_key` string with your own OpenAI
key, then save and close the editor. For example, the structure is:

```json
{"api_key": "YOUR_PRIVATE_OPENAI_API_KEY"}
```

The shipped `openai.example.json` contains only `{"api_key":""}`. The private
`openai.local.json` is never included in the normal distribution ZIP or public
assets. Create and edit it **after Install**, so it inherits the backend's
restricted ACLs. The file belongs beside `server.py`, outside `wwwroot`. The key
is read on the server; the portal has no credential entry control and does not
receive the credential in its JavaScript or API responses. This file is
plaintext protected by filesystem permissions, not application encryption.

Alternatively, the JSON file can reference a separate key file using
`api_key_file`. A relative path resolves against the directory containing the
JSON file. Set either `api_key` or `api_key_file`; do not provide two nonempty
values. For the portable private-directory arrangement below, use:

```json
{"api_key_file": "../private/secrets/openai.key"}
```

The existing masked key setup remains an alternative when `openai.local.json`
is absent:

```powershell
& "$Bundle\deploy\iis\Set-OpenAIKey.ps1" -RuntimeRoot $RuntimeRoot
& $Manage -Action Restart -RuntimeRoot $RuntimeRoot
```

That alternative writes UTF-8 to `$RuntimeRoot\secrets\openai.key` (by default,
`C:\ProgramData\AlloyStudio\secrets\openai.key`). It keeps the key out of
command-line arguments. A configured `openai.local.json` takes priority over this
legacy key-file setting; an empty or invalid config disables Luna rather than
silently selecting another credential. Restart after creating or removing the
JSON file so the launcher selects the intended source.

The backend, config, key directory, and key file permit Administrators/SYSTEM
full control and LOCAL SERVICE read access. Backends and task scripts get
read/execute access for LOCAL SERVICE, while only the separate logs directory
gets modify access. ACLs do not protect secrets from an administrator or another
process already running as LOCAL SERVICE.

The installer writes `backend-task.json` with executable paths and explicit
public origins, then registers and starts `AlloyStudioBackend`. `/alloy/` is
removed from the configured origin: for example, `https://alloy.example.org`.
Multiple approved bindings can be supplied with
`-PublicUrl 'https://alloy.example.org/','https://training.example.org/alloy/'`.
Arbitrary forwarded headers do not authorize another origin.

The default runtime parameters are four workers, a 12-second engine timeout,
and `127.0.0.1:8080`. The port is intentionally shared with the fixed rewrite
rule. This recipe installs one backend per Windows host. The task starts at
boot, ignores overlapping starts, has no execution time limit, and retries
unexpected failures up to 999 times at one-minute intervals. Microsoft documents
[service account task principals](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal?view=windowsserver2025-ps),
[restart settings](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtasksettingsset?view=windowsserver2025-ps),
and [unlimited task runtime](https://learn.microsoft.com/en-us/windows/win32/taskschd/tasksettings-executiontimelimit).
The account must retain the Windows policy permission to run scheduled tasks.

For deterministic feedback without network calls, omit `-EnableLuna`. To change
that option, executable paths, worker settings, or the allowed origin list,
uninstall and reinstall the task with the desired parameters. The uninstall
action preserves the private key and files. Editing an already selected config
or key file needs no reinstall: the explanation client reads it on requests.
`-EnableLuna` is required for either credential-file option. Outbound HTTPS
to the OpenAI API and a valid account are required for Luna explanations;
canonical feedback continues when Luna is unavailable.

## Portable folder arrangement

The same distribution can use a private runtime directory beside `wwwroot`, so
its files have no dependency on a particular Windows user profile or the default
ProgramData location. Run the following from the **extracted distribution root**
in elevated Windows PowerShell. First configure the site's public URL, existing
site/app pool, and this host's machine-wide Python/Java executables as above.

```powershell
$Bundle = (Get-Location).Path
$RuntimeRoot = Join-Path $Bundle 'private'
$BackendRoot = Join-Path $Bundle 'backend'
$WebRoot = Join-Path $Bundle 'wwwroot'
$IisScripts = Join-Path $Bundle 'deploy\iis'
$Manage = Join-Path $IisScripts 'Manage-AlloyStudio.ps1'

& $Manage -Action Install -BackendRoot $BackendRoot -WebRoot $WebRoot `
    -PythonExe $PythonExe -JavaExe $JavaExe -PublicUrl $PublicUrl `
    -RuntimeRoot $RuntimeRoot -EnableLuna
$LunaConfig = Join-Path $BackendRoot 'openai.local.json'
if (-not (Test-Path -LiteralPath $LunaConfig)) {
    Copy-Item -LiteralPath (Join-Path $BackendRoot 'openai.example.json') -Destination $LunaConfig
}
Start-Process -FilePath notepad.exe -ArgumentList ('"' + $LunaConfig + '"') -Wait
& $Manage -Action Restart -RuntimeRoot $RuntimeRoot
& (Join-Path $IisScripts 'Start-AlloyStudio.ps1') `
    -SiteName $SiteName -PoolName $PoolName -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot
& (Join-Path $IisScripts 'Test-IisDeployment.ps1') `
    -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot
& $Manage -Action Status -RuntimeRoot $RuntimeRoot
```

This is an alternative to the default installation above. If that task is already
installed, uninstall it with its original runtime path before installing this
arrangement. The resulting private layout is:

```text
AlloyStudio\
  wwwroot\                    public IIS directory
  backend\                    private engine and exercise data
    openai.example.json       shipped empty credential template
    openai.local.json         your private credential configuration
  deploy\iis\                 administrator scripts
  private\
    backend-task.json         generated configuration for this installation
    secrets\openai.key        optional separate plaintext credential
    logs\backend.log          private runtime log
```

Pass the same `-RuntimeRoot $RuntimeRoot` to every Install, Set-OpenAIKey, Start,
Test, and Manage invocation for this arrangement. For example:

```powershell
& $Manage -Action Stop -RuntimeRoot $RuntimeRoot
& $Manage -Action Start -RuntimeRoot $RuntimeRoot
& (Join-Path $IisScripts 'Test-IisDeployment.ps1') `
    -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -CheckLuna
```

The package root itself must never be an IIS physical directory or the runtime
root: it contains `wwwroot`, and the private-path checks reject that overlap.
`private` is a sibling of `wwwroot`. The normal distribution ZIP contains **no
actual key** and includes neither `openai.local.json` nor this generated private
runtime directory. An administrator may transfer the local config and any
referenced private key file separately when moving an installation, using a
private channel. That transfers plaintext credentials protected by filesystem
permissions, not application encryption. Keep those files out of public
repositories and downloads. The new host can instead fill in its own copy of
the empty config template.

Moving the folder does not move Windows registration. Before moving an existing
installation, stop its IIS site/app pool and unregister its backend task using
the old paths; the uninstall action retains the private files:

```powershell
& $Manage -Action Uninstall -RuntimeRoot $RuntimeRoot
```

At the destination, set the IIS site/application physical path to the new
`wwwroot` and repeat Install with the destination's Python/Java paths, public
URL, and newly derived `$RuntimeRoot`. This overwrites the generated task
configuration, registers the destination's absolute paths, and reapplies the
private ACLs. The IIS site/bindings, TLS certificate, and scheduled task must be
configured on each host; this is not a zero-configuration copy of a running
Windows deployment. Run Start and Test with that same runtime root afterward.

For direct Python launches, `backend/openai.local.json` is discovered beside
`luna.py`. An explicit `OPENAI_CONFIG_FILE` selects another JSON config; relative
config paths resolve against that backend directory, independently of the shell's
working directory. Its `api_key_file` remains relative to the config's own
directory. The legacy `OPENAI_API_KEY_FILE=../private/secrets/openai.key` also
resolves against the backend directory. Absolute paths work for either option.
The IIS launcher clears inherited credential/config settings and selects the
installed `backend/openai.local.json` when present; otherwise it uses the key-file
path generated from `-RuntimeRoot`. It does not select another user's config.

## 5. Verify the actual Windows deployment

```powershell
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot
# Optional: makes a real Luna request using the configured credential.
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -CheckLuna
```

The script runs through IIS, checks the scheduled task's identity, loopback
binding, configured origin, private ACLs and paths, all 181 public exercise
projections, UTF-8 processing, and a real operator repair that reduces canonical
distance from 1 to 0. It checks that denied cross-origin and malformed requests
remain JSON errors and that private routes cannot be downloaded. IIS preserves
backend errors through [`existingResponse="PassThrough"`](https://learn.microsoft.com/en-us/iis/configuration/system.webserver/httperrors/).
The report is `$RuntimeRoot\deployment-check.json` (by default,
`C:\ProgramData\AlloyStudio\deployment-check.json`); a failure
exits with code 1. It contains no HTTP response bodies or credentials. The
optional Luna check verifies availability, not correctness of generated prose.

Also check lifecycle behavior on this server:

```powershell
& $Manage -Action Status -RuntimeRoot $RuntimeRoot
& $Manage -Action Restart -RuntimeRoot $RuntimeRoot
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot
& $Manage -Action Stop -RuntimeRoot $RuntimeRoot
# Expect api/health through IIS to return 502 while static index.html still loads.
& $Manage -Action Start -RuntimeRoot $RuntimeRoot
Restart-WebAppPool -Name $PoolName
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot
```

In an approved maintenance window, restart Windows and rerun the acceptance
script to establish boot startup. To test failure restart, record the Python
PID shown by `Status`, terminate that specific backend process in Task Manager,
wait up to 90 seconds, and verify a new PID and passing health check. This is a
deliberate availability test on the installed host; never terminate unrelated
Python or Java processes. Record these lifecycle observations with the local
acceptance evidence. The delivered Linux closure report does not cover Windows
ACL enforcement, Task Scheduler behavior, real IIS modules, TLS, or host policy.

## Operations and updates

After installation, start the complete IIS application from the configured
elevated 64-bit Windows PowerShell 5.1 session, retaining the selected
`$RuntimeRoot`:

```powershell
& 'C:\Program Files\AlloyStudio\deploy\iis\Start-AlloyStudio.ps1' `
    -SiteName 'AlloyStudio' -PoolName 'AlloyStudio' -PublicUrl 'https://alloy.example.org/' -RuntimeRoot $RuntimeRoot
```

For an existing parent site with the `/alloy/` application, use its site name and
application URL:

```powershell
& 'C:\Program Files\AlloyStudio\deploy\iis\Start-AlloyStudio.ps1' `
    -SiteName 'Existing Site' -PoolName 'AlloyStudio' -PublicUrl 'https://alloy.example.org/alloy/' -RuntimeRoot $RuntimeRoot
```

The starter validates the installed public directory, private backend configuration,
allowed origin, and scheduled task before starting anything. It starts the IIS
prerequisite services WAS/W3SVC when needed, the specified existing app pool and
website, and the installed backend task; success requires all 181 exercises at the
public IIS health endpoint. It performs no installation or binding, certificate,
firewall, ACL, or service startup-policy changes. Optional `-RuntimeRoot` and
`-TaskName` match custom installations; `-UseDefaultCredentials` supports existing
Windows authentication. The selected site and pool use Microsoft's
[site start](https://learn.microsoft.com/en-us/powershell/module/webadministration/start-website?view=windowsserver2025-ps)
and [app pool start](https://learn.microsoft.com/en-us/powershell/module/webadministration/start-webapppool?view=windowsserver2025-ps)
commands. Run the full deployment acceptance script after starting to check repair
behavior and private-route isolation.

`Manage-AlloyStudio.ps1 -Action Status|Start|Stop|Restart|Uninstall` manages the
scheduled backend task. Stop disables it until Start, preventing boot or failure
triggers from undoing an intentional stop. Uninstall removes the task and leaves
configuration, application files, logs, and the key. No Windows Service Control
Manager service is installed.

Backend startup messages go to the private `$RuntimeRoot\logs\backend.log`
(by default, `C:\ProgramData\AlloyStudio\logs\backend.log`). The HTTP backend does not log
learner request bodies or oracle data. Rotate this log during maintenance if
needed. IIS logs and Task Scheduler's Operational log remain host-managed.

For an update, stop the task and the IIS site/application pool, privately back up
the old distribution, extract the new distribution into a fresh local directory,
and point the IIS physical path at the new `wwwroot`. Uninstall and install the
task against the new private backend and launcher directories. Repeat the ACL
step and acceptance tests before restoring traffic. Roll back by restoring the
old physical path and task registration. Never copy a source checkout or the
private `backend` contents into the public directory.

| Symptom | Check |
| --- | --- |
| IIS 500.19 | URL Rewrite/ARR installation, locked configuration sections, parent rules, and the parsed `web.config` error in IIS logs. |
| IIS 502.3 | Task state, private `backend.log`, the Python/Java paths, port 8080, and ARR proxy timeout. |
| API POST returns 403 | Exact public scheme, hostname and port in `-PublicUrl`; re-register after changing bindings. |
| Java analysis unavailable | Java 17+ runtime, LOCAL SERVICE read/execute access, and packaged classes/JARs. |
| Task fails after boot | Machine-wide runtime paths and task-account policy; inspect Task Scheduler history. |
| Luna is disabled/unavailable | Install with `-EnableLuna`, check the private `openai.local.json` values and ACLs, restart after adding/removing that config, and check outbound HTTPS/account access. The masked key setup is available when the config is absent. |
| `/alloy/` assets or API fail | Use an IIS application and its trailing-slash URL; inspect inherited rewrite/authentication rules. |
