# Deploy Alloy Studio on IIS 10.0

IIS serves the portal, dashboard and administration assets and proxies this application's `api/*` requests
to Python at `127.0.0.1:8080`. Python runs the bundled Java canonical engine and
keeps reference solutions and Luna credentials out of browser responses. The
backend runs as a Windows **scheduled task**, under LOCAL SERVICE, independently
of IIS app pool recycling. Both a dedicated website and an application such as
`/alloy/` work.

The package preserves all 181 exercises and their existing environments, plus
correct-predicate pools with 7,550 corpus candidates and 181 oracles. Each request
compares all admitted candidates and traces one nearest match. These data are
also tracked in the public repository: anyone reading or cloning it can inspect
reference solutions. Browser redaction does not make that source data secret.
The ZIP is a server distribution; never make its package root an IIS physical
directory. Only `wwwroot` is served publicly. No API key is included in the
distribution.

Every bundled invariant has a natural-language requirement displayed above the
editor. The private SQLite database stores those descriptions, predicate bodies,
ordered correct pools and primary oracle identities. A running backend reads one
consistent snapshot. Host-side CLI additions require a restart; authenticated
browser publication refreshes the live snapshot atomically.

To add an exercise, run the host-only command from `backend` with a validated
exercise import file (see [the storage security specification](../../docs/sqlite-security-spec.md)):

```powershell
python .\scripts\manage_exercises.py validate C:\Private\exercise.json
python .\scripts\manage_exercises.py add C:\Private\exercise.json
```

One or more oracle bodies are required. The first is the behavioral reference;
all oracles participate in nearest-correct selection. The importer checks the
starter and solutions with Alloy and requires bounded equivalence to the first
oracle. The authenticated `/admin/` interface also accepts complete `.als` uploads.
It is disabled until a host administrator configures a password; see
[administration setup](../../docs/admin-setup.md) in the source checkout, or
`backend/docs/admin-setup.md` in the extracted package. Keep import files outside
every IIS public directory, and preserve the installed database when updating code.

The `Native IIS acceptance` Actions workflow provisions Windows Server 2022,
IIS 10, URL Rewrite 2.1 and ARR 3.0, then installs the packaged backend as a real
LOCAL SERVICE task. Its sanitized report is separate from the portable Linux,
macOS and Windows tests. A successful native run validates that runner and
revision; it does not establish that a particular production server is configured
correctly. Run the acceptance script below on the actual target as well. The
workflow does not upload the private package, database, logs or credentials.

## Administration boundary

The `/admin/` login shell contains no credentials or private data. Run
`python scripts/configure_admin.py --origin https://as.555.is --base-path /`
from the private backend to set its password interactively; adjust the exact
origin and application path for the deployment. For an application at
`https://example.org/alloy/`, use origin `https://example.org` and base path
`/alloy/`. Do not put the password in a command-line argument. Only its salted
scrypt hash is stored in the private `admin.local.json`, which is excluded from
Git and deployment archives. Missing configuration leaves administration disabled.

The installer gives LOCAL SERVICE Modify access only to `backend/exercises`
for SQLite publication and journals. Code, password configuration and provider
credentials retain read-only access. Existing installs must apply these updated
ACLs as part of their upgrade; do not grant write access to the entire backend.
An operator who configures the password after installation should retain the
private backend's inherited ACLs. The Windows acceptance script checks the
intended scope, including the new admin configuration when present.

Configure Cloudflare to bypass caching `/api/admin/*` (including any application
prefix). Preserve `Cache-Control: no-store, private` and `Set-Cookie`; no admin
API response should enter a shared cache. Production requires HTTPS, and no
forwarded Host/protocol header chooses authentication authority. Same-origin
applications are trusted; a URL path cannot isolate an untrusted sibling app.
Preparation and suggestions use bounded jobs so their API requests do not stay
open through a full solver/provider run. These administration jobs do not require
an additional ARR timeout increase beyond the analysis budget documented below.

### Administration network admission (default deny)

Administration is denied at both the IIS edge and the backend unless an operator
network is configured (AP01-C11). By default every `/admin` and `/api/admin`
request returns 404 before any sign-in cookie, CSRF token or failed-login count
is created. The admin UI also passes through the backend; IIS does not serve an
admitted `/admin` request directly from its static directory.

For a browser connecting directly to IIS, configure **both** layers:

1. In the deployed `wwwroot/web.config`, add one negated condition per address to
   the `Administration network policy` rule, for example
   `<add input="{REMOTE_ADDR}" pattern="^192\.0\.2\.10$" negate="true" />`.
2. Configure ARR to append canonical client addresses without ports, as described
   [below](#client-identity-for-editing-channels-optional). Add
   `-TrustedProxy 127.0.0.1 -AdminNetwork 192.0.2.10/32` to the task's `Install`
   command. Trusting the local IIS hop is required: otherwise the backend sees
   `127.0.0.1`, which does not belong to the operator's network.

Keep the private `deploy/iis/web.config` reference unchanged.
`Start-AlloyStudio.ps1` accepts only these exact, canonical `REMOTE_ADDR`
conditions in the public configuration. It rejects wildcard patterns,
forwarded-header conditions and changes to any other XML setting. Each address
needs its own negated condition; the conditions use `MatchAll`, so an address
matching any configured exception bypasses the edge deny rule. The backend
additionally accepts canonical CIDR blocks; the packaged edge customization
supports exact addresses only.
When upgrading a package, record these edge exceptions and reapply them to the
new public template; replacing `wwwroot/web.config` restores default deny.

Behind Cloudflare, `REMOTE_ADDR` at IIS is the connecting Cloudflare edge,
whereas the backend's administration policy must select the original client.
Restrict the administration paths at Cloudflare, permit only the connecting
edge addresses in the IIS rule, and configure the backend's `-TrustedProxy`
with `127.0.0.1` **and every exact trusted forwarding hop**. Keep `-AdminNetwork`
limited to the operator's client network. Allowing an edge address at IIS alone
does not admit an operator at the backend; changing only Cloudflare policy also
does not override the default IIS deny rule. Inspect the actual forwarding chain
before enabling this path. Direct origin access for administration is another
option when its network policy permits it.
Use the public HTTPS origin in administrator configuration. The local HTTP
exception applies to a resolved loopback client only; forwarding through the
local IIS process does not make an external client eligible for HTTP admin.
From an unlisted address, `/admin/` and `/api/admin/session` must return 404; from
a listed address the sign-in page loads. Allowed sources still share the
five-failure sign-in limit. Verifying the deployed network path remains an
operator acceptance step.

Optional `-ControlPort 8081` on `Manage-AlloyStudio.ps1 -Action Install` enables
the private loopback diagnostics listener, provided that port is unused.
`-Action Status` then includes `/api/diagnostics`; IIS continues to proxy only
the application port. `-EngineMode persistent` is the default; `oneshot` remains
an explicit rollback option. Installation validates proxy/network spellings
before creating task configuration. Existing task configurations must be
updated through the documented stop/uninstall/install procedure to change these
options; a normal restart preserves them.

## 1. Build and transfer the package

From a fresh source checkout, build the Java engine and create the distribution
with one command from the checkout root.
The repository includes `exercises/exercises.sqlite3`, so no original ACGN or
SQLeanParser checkout, Lean installation, or existing IIS ZIP is required.
Packaging validates the database and uses SQLite backup to capture a consistent
committed snapshot, including administrator additions. Only the database ships
as exercise storage; legacy JSON files remain migration inputs and witnesses. See
[exercise data and provenance](../../exercises/README.md) for their contents.

```bash
./scripts/build.sh
```

Git Bash, MSYS2 and Cygwin on Windows can use the same `./scripts/build.sh`
command. It detects the Windows shell, converts paths with `cygpath`, and
delegates to the native PowerShell builder. Update the complete checkout,
including the Bash/PowerShell entry points, `scripts/build-windows.sh`,
`scripts/build_engine.py`, and `scripts/package_iis.py`. The
Windows PowerShell source-build equivalent is:

```powershell
powershell -NoProfile -File scripts/build.ps1 -RequireNode
```

The release build uses a JDK 17 or newer, Python 3.10 or newer, and Node 20 or
newer for the JavaScript syntax check. Each command creates
`build/iis/alloy-studio-iis-<UTC timestamp>.zip` and its matching `.zip.sha256`
sidecar after successful checks. The timestamp includes microseconds, and the
command prints the exact filename. Earlier archives remain untouched, so a
locked older ZIP does not need to be replaced. Optional `-JavaCompiler` and `-Python` arguments accept absolute
executable paths and are forwarded through compilation and packaging.
`-OutputDirectory` selects the compiled-class directory. `-EngineOnly`
compiles without creating or updating a ZIP; direct `./engine/build.sh` does
the same and defaults to the project-root `build/engine/classes` directory.
Custom class outputs must be dedicated directories containing only compiled
classes; protected project directories and unrelated files are refused.

The portal builder validates its inputs and frontend before packaging fresh
Java classes. The shared compiler is `scripts/build_engine.py`. A failure in
compilation, frontend validation, or package preflight stops the command; it
does not report that the ZIP was refreshed. An existing ZIP and checksum are
preserved as the **older successful build**. Transfer a package only after a
successful build.

The standalone `python scripts/package_iis.py` command also compiles Java from
source before packaging, so it now requires a JDK. It accepts `--javac` for a
custom compiler, `--classes-output` for its class output, `--source` for an
alternate checkout, and `--output` for a custom private ZIP path. Use `python3`
where that names the installed Python 3.10+ interpreter. This standalone
command does not run the Node syntax check; use the portal build command above
for that check.

Packaging gives the script and stylesheet URLs in `wwwroot/index.html` a
`?v=<SHA-256>` suffix computed from each asset's bytes. The source
`web/index.html` is unchanged. The instance renderer is also packaged as
`instance-graph.js`; its import in packaged `app.js` receives its own content
hash before the application hash is computed. A renderer-only change therefore
refreshes both URLs. The source JavaScript remains unchanged. Copy this module
with the other public files when upgrading. The included `web.config` configures
`Cache-Control: no-cache, no-store` for static content, suppresses static ETags,
and disables IIS output and kernel caching for this application. Verify the
effective response headers on the target host. Preserve these
settings when merging site-specific configuration. Cloudflare rules must honor
the origin cache policy and versioned query strings; an override that caches this
application despite `no-store` must be removed or scoped away from it.

These build tools stay in the source checkout and are excluded from the runtime
ZIP. The IIS runtime still needs no Node, npm, Bash, JDK/compiler, pip packages,
or original ACGN/SQLeanParser checkout or Lean installation. Use a machine-wide
64-bit Python installation and Java 17+ runtime readable by LOCAL SERVICE; avoid
the Microsoft Store Python alias or executables in a user's private profile.

If the bundled data are missing or damaged, preserve intentional local data
edits and explicitly restore the seed database from Git before rebuilding:

```powershell
git restore --source=HEAD -- exercises/exercises.sqlite3
```

For optional custom imports or legacy checkouts without tracked data, the build
can import an original `classified-data/` corpus using
`-ACGNRoot C:\alloystudio\ACGN` on PowerShell, or `ACGN_ROOT` on Bash. A trusted
IIS ZIP is also an optional recovery source:

```powershell
python .\scripts\prepare_private_data.py --from-bundle 'C:\Staging\alloy-studio-iis.zip'
```

The same helper works on Linux/macOS with the appropriate ZIP path and Python
command. It verifies manifest hashes and the database before restoring only
exercise data. Older JSON-pair bundles are supported through validated migration.
An existing database is authoritative, even when legacy JSON differs; an invalid
existing database fails rather than falling back to JSON. Back up and move the
database explicitly before intentionally replacing a dataset. Incomplete legacy
pairs and invalid inputs are left untouched. Database, journal/WAL/SHM files and
query artifacts belong outside the public IIS directory.

Transfer the timestamped ZIP printed by the build and its `.sha256` sidecar through a private
channel and compare the archive's SHA-256 with the sidecar after transfer. In an elevated
**64-bit Windows PowerShell 5.1** session on the Windows server, substitute your
actual package filename below:

```powershell
$ErrorActionPreference = 'Stop'
$Bundle = 'C:\Program Files\AlloyStudio'
Expand-Archive -LiteralPath 'C:\Staging\alloy-studio-iis-20260928-231500-123456Z.zip' -DestinationPath $Bundle
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
  wwwroot\              portal files, web.config, dashboard/ and admin/ ONLY
  backend\              Python code, classes, JARs, catalogue and correct pools
  deploy\iis\           administrator scripts and the task launcher
  manifest.json         packaged file hashes
```

Use regular local directories for Alloy's bundle and task state. If a directory
or one of its parents is a junction or symbolic link, the error identifies the
offending path and parameter; use its real directory path or move the bundle.
Java/Python executable links (including linked parent directories) are resolved
to real local files before the scheduled task is registered. Other sites may
use local directory junctions: their resolved roots still participate in the
private/public overlap check. Broken links, unsupported reparse points such as
Microsoft Store execution aliases, and network targets fail with a named path.
The default task state is stored in `C:\ProgramData\AlloyStudio`.

For a Store Python alias, select a machine-wide Python installation readable by
LOCAL SERVICE and pass its actual `python.exe` path with `-PythonExe`.
The resolver uses Windows [GetFinalPathNameByHandleW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfinalpathnamebyhandlew)
to resolve parent links and short names before checking path overlap. Native
path regression fixtures are available in a source checkout:

```powershell
powershell.exe -NoProfile -File .\tests\iis_paths.Tests.ps1
```

Run elevated to permit creation of temporary file symlinks. These tests create
and remove only their own temporary fixture tree; they do not change IIS or
task configuration. Linux policy fixtures in `tests/iis_path_policy.Tests.ps1`
use simulated filesystem calls and do not substitute for the native tests.

Check the transferred backend before registering it with IIS:

```powershell
& $PythonExe -E -s (Join-Path $BackendRoot 'runtime_dependencies.py') --root $BackendRoot --java $JavaExe
if ($LASTEXITCODE -ne 0) { throw 'Restore the missing or changed runtime files identified in the JSON report.' }
```

This target-side check requires the complete bundled dependency set, verifies
each JAR's SHA-256 against `backend/vendor/acgn/snapshot.json`, checks the
compiled entry classes, and runs 500 engine checks in a fresh JVM. The engine
uses an explicit classpath built from these files and ignores ambient Java
classpath/option settings. The check needs no compiler, download, network
connection, credential, or original ACGN checkout. Retain all seven files under
`backend/vendor/acgn/lib`:

| File | Contents |
| --- | --- |
| `AlloyASG-Release.jar` | Compiled AlloyASG classes and bundled third-party classes. |
| `AlloyASG.jar` | Source-only archive preserved from the framework snapshot. |
| `AlloyParser.jar` | Parser AST classes. |
| `alloy.jar` | Alloy parser and runtime classes. |
| `commons-cli-1.4.jar` | Command-line parsing dependency. |
| `json-java.jar` | JSON dependency. |
| `slf4j-simple-1.7.36.jar` | Logging implementation. |

The release JAR contains duplicate classes from the other compiled JARs, and
`AlloyASG.jar` contains source files rather than `.class` files. A successful
single repair request therefore cannot prove that every declared dependency was
copied. The inventory/hash checks reject a missing, changed, or extra JAR before
the JVM smoke test. Copy the entire private `backend` directory from the ZIP;
copying only the public assets or Python files does not install the engine.
Missing dependencies are identified by filename in the report.

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
& $AppCmd set config -section:system.webServer/proxy /enabled:true /commit:apphost
if ($LASTEXITCODE -ne 0) { throw 'ARR proxy configuration failed.' }
$Proxy = Get-WebConfiguration -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy'
# Preserve a longer timeout already required by another website.
if (([TimeSpan]$Proxy.timeout).TotalSeconds -lt 120) {
    & $AppCmd set config -section:system.webServer/proxy /timeout:00:02:00 /commit:apphost
    if ($LASTEXITCODE -ne 0) { throw 'ARR timeout configuration failed.' }
}
```

This changes ARR's **server-wide** proxy setting, which is shared with other IIS
sites; the command retains any existing timeout longer than 120 seconds. Retain
at least 120 seconds: the largest supported IIS analysis envelope is a 30-second
worker acquisition/startup budget, 30-second calculation, five-second scheduler
queue, one-second completion allowance and five-second response-write budget
(71.25 seconds including up to 0.25 seconds of request cleanup). The browser
bounds each analysis request at 150 seconds. Default
constrained-profile startup is 20 seconds; feedback calculation remains 12
seconds and behavior 30 seconds. Startup no longer consumes the calculation
budget. The browser normally supplies an evidence token for Luna, avoiding a
second feedback computation. A legacy explanation request without that token
may first recompute feedback and then call the provider with a 40-second socket
timeout: the nominal phase-budget sum is 111.25 seconds (83.25 with default
budgets). The provider timeout is not a proven absolute bound on the entire
response read; network progress and runtime preemption can extend it. Behavioral
examples use their own request. These sums are planning targets, exclude host
scheduling delays, and are not a guaranteed end-to-end deadline. ARR and the
browser retain their independent outer timeouts. The included
`web.config` has only a fixed loopback upstream. Administration is gated first,
API/admin requests are proxied next, and the final rule limits public assets.
No wildcard filesystem handler exposes the backend.
Application-relative rewrite matching also supports `/alloy/api/...`; see the
[URL Rewrite configuration reference](https://learn.microsoft.com/en-us/iis/extensions/url-rewrite-module/url-rewrite-module-configuration-reference).

For proxied Cloudflare DNS, its [published origin connection limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/)
list a 125-second proxy read timeout and 30-second proxy write timeout (checked
2026-10-05). The nominal 111.25-second legacy explanation phase sum fits that
published read limit, but it is not an absolute provider-read guarantee. Verify
the actual zone and any other upstream proxy
settings. Raising ARR or the browser deadline does not raise Cloudflare's limit.
The deployment scripts do not modify Cloudflare. Slow or overloaded origins can
still trigger [Cloudflare 524](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/).

### Client identity for editing channels (optional)

By default the backend trusts no proxy, so every request forwarded by IIS shares
the loopback identity for the 32-channel editing quota (512 site-wide). When that
quota is full, browsers fall back to channel-less checks for that operation: they
keep all local stale-result guards, but the server cannot supersede or cancel
their work. To give each client its own quota identity (AP01-C04/C05):

```powershell
& $AppCmd set config -section:system.webServer/proxy /includePortInXForwardedFor:false /commit:apphost
if ($LASTEXITCODE -ne 0) { throw 'ARR X-Forwarded-For configuration failed.' }
```

This ARR setting is server-wide; account for the other sites on the Windows
host before changing it. Then install the backend task with
`-TrustedProxy 127.0.0.1`. The backend accepts
exactly one `X-Forwarded-For` field of at most 4096 bytes and 32 canonical
addresses (no ports or zones), scans from the nearest hop, and skips only listed
proxies. Malformed or absent metadata from a trusted proxy rejects channel
creation; it never falls back to the proxy address. With Cloudflare in front,
either list each exact Cloudflare address you rely on with `-TrustedProxy`, or
accept the Cloudflare edge address as the quota identity. A header never grants
trust by itself. Connection admission still counts the TCP peer (IIS), so size
the traffic profile for the proxy's aggregate load. Confirm on the target how
each proxy appends and sanitizes the header before relying on per-client quotas.

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
read/execute access for LOCAL SERVICE. The private exercises directory and
separate logs directory receive Modify access; code and credentials do not. ACLs do not protect secrets from an administrator or another
process already running as LOCAL SERVICE.

The installer writes `backend-task.json` with executable paths and explicit
public origins, then registers and starts `AlloyStudioBackend`. `/alloy/` is
removed from the configured origin: for example, `https://alloy.example.org`.
Multiple approved bindings can be supplied with
`-PublicUrl 'https://alloy.example.org/','https://training.example.org/alloy/'`.
Arbitrary forwarded headers do not authorize another origin.
Install runs the complete dependency preflight before applying ACLs or registering
the task. Start and Restart repeat it before changing task state, using the
Python executable recorded in the scheduled task and the configured Java runtime.
If a required JAR is missing, a running backend is not stopped by Restart.

The default engine mode is `persistent`. The default `constrained` resource
profile retains one feedback JVM and one behavioral JVM, with a separate
administrator process slot; each Java child is told to use one processor.
`-ResourceProfile standard` retains two feedback JVMs and one behavioral JVM,
with two processors available per Java child. Both profiles preserve the same
complete correct-pool comparison, canonical/AST algorithms, work limits and
behavioral examples. Existing explicit `workers: 4` values remain valid and are
capped at two feedback workers. `-Workers 0` (the installer default) selects the
profile default without writing an override. A 12-second feedback calculation
timeout and `127.0.0.1:8080` remain the defaults. `-StartupTimeout 1..30` overrides
the acquisition/startup budget; zero omits the override (20 seconds constrained,
10 seconds standard). `-EngineTimeout 1..30` controls the calculation budget
separately. The installer and task launcher reject larger IIS budgets so they
fit the 120-second proxy envelope.
The port is intentionally shared with the fixed rewrite
rule. This recipe installs one backend per Windows host. The task starts at
boot, ignores overlapping starts, has no execution time limit, and retries
unexpected failures up to 999 times at one-minute intervals. Microsoft documents
[service account task principals](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal?view=windowsserver2025-ps),
[restart settings](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtasksettingsset?view=windowsserver2025-ps),
and [unlimited task runtime](https://learn.microsoft.com/en-us/windows/win32/taskschd/tasksettings-executiontimelimit).
The account must retain the Windows policy permission to run scheduled tasks.

The private `backend-task.json` accepts optional runtime fields without
changing the provider key or administrator password configuration:

```json
"engine_mode": "persistent",
"control_port": 0,
"resource_profile": "constrained"
```

Add these fields to the existing JSON object, preserving its other fields and
restricted ACLs. Older files that omit them use these defaults; explicit existing
`workers` values are retained. An optional `startup_timeout` is a positive number
at most 30; omit it to use the profile default. To roll back to
per-request Java execution, change `engine_mode` to `"oneshot"` and restart the
backend task. This retains request validation, work-sharing and output checks;
it changes the Java process lifecycle. The source launcher exposes the same
choice as `--engine-mode oneshot`.

For independent local health checks, set `control_port` to an unused port such
as `8081` and restart. The listener binds only to `127.0.0.1`, has a separate
bounded admission budget, and serves `http://127.0.0.1:8081/api/health`. It does
not serve analysis, assets or administration. Zero disables it. Keep the fixed
IIS rewrite upstream on port `8080`; do not route the control listener through
IIS or Cloudflare. A malformed setting or port `8080` is rejected before startup.

Normal Ctrl+C/SIGTERM shutdown closes and reaps the backend's worker processes.
A forced Task Scheduler stop or host crash can bypass Python cleanup; this
implementation does not establish Windows Job Object containment. Check for
orphan Java processes during the target-host lifecycle acceptance below. CI now
exercises native Windows and macOS worker transport and request boundaries, but
its results do not replace that IIS host check.

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
  backend\                    server-only engine and exercise data
    openai.example.json       shipped empty credential template
    openai.local.json         your private credential configuration
  deploy\iis\                 administrator scripts
  private\
    backend-task.json         generated configuration for this installation
    secrets\openai.key        optional separate plaintext credential
    logs\backend.log          private runtime log
    logs\engine-tmp\          private per-JVM scratch, removed after each analysis
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
# When the operator address is explicitly admitted by both network policies:
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -ExpectAdminAccess
# Optional, with an installed nonzero ControlPort; checks actual worker PID reuse:
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -CheckDiagnostics
# Optional: makes a real Luna request using the configured credential.
& "$Bundle\deploy\iis\Test-IisDeployment.ps1" -PublicUrl $PublicUrl -RuntimeRoot $RuntimeRoot -CheckLuna
```

The script first records all seven dependency filenames, expected/actual SHA-256
hashes, compiled-class results, and the fresh JVM's 500-check result. Those
details remain in `runtime_dependencies` even when a dependency failure prevents
HTTP tests. It then runs through IIS, checks the scheduled task's identity, loopback
binding, configured origin, private ACLs and paths, all public exercise
projections, UTF-8 processing, and a real operator repair that reduces canonical
distance from 1 to 0, raw AST edits, and the four fact-constrained behavioral
categories with at most three concrete graph inputs each. It checks the actual
ARR setting is at least 120 seconds and allows 130 seconds for its own request
probe. Default administration must return 404; use `-ExpectAdminAccess` only
when the current operator network has been configured at both layers. The
optional `-CheckDiagnostics` mode requires persistent workers and a configured
private control port, verifies ready lanes and stable Java process IDs across
fresh analyses, and checks IIS cannot expose private diagnostics.
It checks that denied cross-origin and malformed requests
remain JSON errors and that private routes cannot be downloaded. IIS preserves
backend errors through [`existingResponse="PassThrough"`](https://learn.microsoft.com/en-us/iis/configuration/system.webserver/httperrors/).
The report is `$RuntimeRoot\deployment-check.json` (by default,
`C:\ProgramData\AlloyStudio\deployment-check.json`); a failure
exits with code 1. It contains no HTTP response bodies or credentials. The
optional Luna check verifies availability, not correctness of generated prose.

The native Actions harness `scripts/test_native_iis.ps1` runs only on a disposable
Windows Actions runner. It pins both Microsoft installers by SHA-256 and checks
their Authenticode signatures, installs a separate sentinel site, then executes
the real package's installer, startup and acceptance scripts. The sentinel must
retain its binding and response after Alloy starts and stops. Only fixed check
names/statuses and runner revision enter the uploaded summary. Its HTTP-loopback
test does not validate production TLS, Cloudflare, Windows client editions,
machine reboot or a particular user's host configuration.

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
website, and the installed backend task; success requires a positive exercise count matching the listing at the
public IIS health endpoint. It performs no installation or binding, certificate,
firewall, ACL, or service startup-policy changes. Optional `-RuntimeRoot` and
`-TaskName` match custom installations; `-UseDefaultCredentials` supports existing
Windows authentication. The selected site and pool use Microsoft's
[site start](https://learn.microsoft.com/en-us/powershell/module/webadministration/start-website?view=windowsserver2025-ps)
and [app pool start](https://learn.microsoft.com/en-us/powershell/module/webadministration/start-webapppool?view=windowsserver2025-ps)
commands. Run the full deployment acceptance script after starting to check repair
behavior and private-route isolation.
The full dependency preflight also runs before the starter changes any IIS
service, app pool, or website state.

`Manage-AlloyStudio.ps1 -Action Status|Start|Stop|Restart|Uninstall` manages the
scheduled backend task. Stop disables it until Start, preventing boot or failure
triggers from undoing an intentional stop. Uninstall removes the task and leaves
configuration, application files, logs, and the key. No Windows Service Control
Manager service is installed.

Backend startup messages go to the private `$RuntimeRoot\logs\backend.log`
(by default, `C:\ProgramData\AlloyStudio\logs\backend.log`). The HTTP backend does not log
learner request bodies or oracle data. Rotate this log during maintenance if
needed. IIS logs and Task Scheduler's Operational log remain host-managed.

### Updating an existing installation

**An old page can be a cached asset.** Inspection of `https://as.555.is/` confirmed
old, unversioned `app.js` and `styles.css` served as Cloudflare `HIT` entries with
a four-hour cache lifetime. Fresh query URLs returned current asset hashes, and
the behavioral API exposed the new score and categories. The served homepage
HTML was still an older version missing the new panels, including on fresh-query
requests. Update all packaged public files, including the `dashboard` and `admin` directories, in the active IIS directory using the
steps below; purging JavaScript alone cannot add missing HTML panels. Purge this
website's homepage, `index.html`, `app.js`, and `styles.css` in Cloudflare, then
hard-reload the browser. Rebuilding the ZIP or restarting IIS does not clear
existing CDN/browser entries. This public inspection does not identify the
server's installed filesystem paths or distinguish an old file on disk from an
origin cache serving it.

For a new release, extract the successfully built ZIP into a fresh **private
staging directory** and back up the installed application. Building or extracting
a ZIP does not update the files already used by IIS and its scheduled task.
Discover the installed paths; do not assume the staging folder is the live one.
Replace the example site/task names if yours differ. These diagnostic commands
are each a single PowerShell line with explicit semicolons, so pasted line
wrapping cannot join commands accidentally:

```powershell
Import-Module WebAdministration; $SiteName = 'as'; $TaskName = 'AlloyStudioBackend'; Get-Website -Name $SiteName | Select-Object Name,PhysicalPath,ApplicationPool,State; Get-ScheduledTask -TaskName $TaskName | ForEach-Object { $_.Actions } | Select-Object Execute,WorkingDirectory,Arguments
```

Use this installation's task name and `$RuntimeRoot` (the directory containing
`backend-task.json` in the task's `--config` argument), then set the live paths:

```powershell
$Installed = Get-Content -LiteralPath (Join-Path $RuntimeRoot 'backend-task.json') -Raw -Encoding UTF8 | ConvertFrom-Json; $BackendRoot = [string]$Installed.backend_root; $WebRoot = [Environment]::ExpandEnvironmentVariables([string](Get-Website -Name $SiteName).PhysicalPath); $BackendRoot; $WebRoot
```

1. Stop the backend and only the dedicated Alloy website using the existing
   `$Manage` path. For a virtual application, use its own physical path and
   maintenance procedure instead of stopping a shared parent website.

   ```powershell
   & $Manage -Action Stop -RuntimeRoot $RuntimeRoot -TaskName $TaskName; Stop-Website -Name $SiteName
   ```

2. Copy all new `wwwroot` files, including the `dashboard` and `admin` directories, into the **installed** `$WebRoot`, retaining
   intentional site settings and the new cache controls in `web.config`. Update
   the installed private backend and deployment launchers from the same package;
   replace its class tree and JAR directory as complete units. Preserve
   `backend/openai.local.json`, `backend/admin.local.json`, referenced key files,
   runtime configuration and secrets. Preserve the installed `exercises/exercises.sqlite3` and all committed
   administrator additions. With the backend and writers stopped, back up the
   database before updating. Replace it only when intentionally changing datasets;
   do not overwrite it with the bundled seed during a routine code upgrade. Never
   copy the backend or package root into the public IIS directory.

   With all database writers still stopped, apply the current data permission
   policy using the updated manager script. Older installations made this
   directory read-only, which prevents publication from the new admin page:

   ```powershell
   & $Manage -Action UpdateDataPermissions -RuntimeRoot $RuntimeRoot -TaskName $TaskName
   ```

   This updates only the private `exercises` directory ACL to LOCAL SERVICE
   Modify. It checks the installed paths against every IIS physical directory
   and refuses links. It does not replace the database, rewrite task settings,
   or grant writes to backend code, `admin.local.json` or OpenAI credentials.

3. Touch only these installed public files after copying. Reproducible ZIPs use
   1980 timestamps; the inspected origin reused that `Last-Modified` and ETag
   across changed bytes, allowing stale timestamp-based validation. Updating
   timestamps leaves the package's content hashes unchanged:

   ```powershell
   @('index.html','app.js','instance-graph.js','styles.css','web.config','dashboard/index.html','dashboard/app.js','dashboard/styles.css','dashboard/data.json','admin/index.html','admin/app.js','admin/styles.css') | ForEach-Object { (Get-Item -LiteralPath (Join-Path $WebRoot $_)).LastWriteTimeUtc = [DateTime]::UtcNow }
   ```

4. Restart the backend and start only the selected website:

   ```powershell
   & $Manage -Action Restart -RuntimeRoot $RuntimeRoot -TaskName $TaskName; Start-Website -Name $SiteName
   ```

   `Start` does not replace an already running Python process. Recycling an IIS
   pool does not restart the scheduled backend. If a pool recycle is needed, use
   `Restart-WebAppPool -Name $PoolName` only when that pool is dedicated to Alloy.
   Do not restart all IIS services or recycle a shared pool.

5. Purge this website's homepage, `index.html`, `app.js`, `instance-graph.js`, and `styles.css` URLs in
   Cloudflare, including cached versioned variants when applicable; do not purge
   unrelated sites in the zone. Hard-reload the browser and verify that HTML uses
   the SHA-256 asset URLs. Cache rules or Workers must not override this site's
   `no-store` policy or ignore query versions. Run `Test-IisDeployment.ps1` with
   the existing runtime root before recording deployment acceptance.

If backend or launcher paths change, uninstall and reinstall the task against
those new paths and reapply the private ACLs; changing IIS's physical path alone
does not move the backend. Retain the previous distribution and task settings for
rollback. Build/package tests on Linux do not establish native Windows acceptance.

If the portal reports an unreadable response, run this read-only check **on the
IIS host**. It requires Python, but no administrator privileges or installed task
configuration. Use the deployment's actual public URL, including `/alloy/` when
it is an IIS application:

```powershell
.\deploy\iis\Test-ApiConnection.ps1 -PublicUrl 'https://alloy.example.org/alloy/' -PythonExe 'C:\Python312\python.exe'
```

It compares public and loopback `api/health` requests, without following redirects
or changing IIS. Output contains HTTP status, content-type category, health
validation, and exercise counts; it excludes response bodies, URLs, and secrets.
Each request has a five-second deadline. `local_backend_unavailable` means the
loopback request failed: inspect the backend task and protected log.
`public_proxy_or_routing` means loopback health passed while public health did
not: inspect ARR, application paths, bindings, and inherited rules.
`public_authentication_or_access` and `public_redirect` identify an access
response or redirect; check the site's intended authentication policy. This
diagnostic sends no Windows credentials. `catalogue_mismatch` means both
endpoints returned health data with different exercise counts; check which
backend the site routes to. The output retains both request results when more
than one layer fails. A passing check confirms health connectivity and matching
counts; run `Test-IisDeployment.ps1` for the full deployment acceptance checks.

| Symptom | Check |
| --- | --- |
| Old page after a successful build | Verify the installed IIS and task paths, copy the new files there, refresh public-file timestamps, restart the backend, and purge this website's browser/CDN caches as described above. |
| IIS 500.19 | URL Rewrite/ARR installation, locked configuration sections, parent rules, and the parsed `web.config` error in IIS logs. |
| IIS 502.3 | Task state, private `backend.log`, the Python/Java paths, port 8080, and ARR proxy timeout. |
| API POST returns 403 | Exact public scheme, hostname and port in `-PublicUrl`; re-register after changing bindings. |
| Java analysis unavailable | Java 17+ runtime, LOCAL SERVICE read/execute access, and packaged classes/JARs. |
| Missing/changed dependency or Java packages not found | Run `runtime_dependencies.py` with `--java`; restore all seven JARs from the same distribution instead of relying on duplicate classes in the release JAR. A source build also requires the complete `vendor/acgn/lib` directory. |
| Task fails after boot | Machine-wide runtime paths and task-account policy; inspect Task Scheduler history. |
| Luna is disabled/unavailable | Install with `-EnableLuna`, check the private `openai.local.json` values and ACLs, restart after adding/removing that config, and check outbound HTTPS/account access. The masked key setup is available when the config is absent. |
| `/alloy/` assets or API fail | Use an IIS application and its trailing-slash URL; inspect inherited rewrite/authentication rules. |
