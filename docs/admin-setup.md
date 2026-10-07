# Configure model upload administration

The public `/admin/` page starts with a login form. Administration is disabled
until an administrator creates a private password configuration on the backend
host. There is no default password, and deployment archives contain no password
configuration. The learner portal works without enabling administration.

## Set a password on the host

From the source checkout, or the private `backend` directory in an IIS package:

```bash
python3 scripts/configure_admin.py --origin http://127.0.0.1:8080 --base-path /
```

Use `python` on Windows where appropriate. The command prompts twice without
echoing the password. Choose a password with at least 12 UTF-8 bytes; the maximum
is 1,024 bytes. Do not place passwords in command-line arguments, environment
variables, import files or the public web directory. The command writes only a
salted scrypt hash and deployment settings to `admin.local.json`; POSIX mode is
0600. Linked configuration paths are refused.

For an HTTPS IIS site, use its exact browser origin:

```powershell
python .\scripts\configure_admin.py --origin https://as.555.is --base-path /
```

For an IIS application at `https://example.org/alloy/`, configure
`--origin https://example.org --base-path /alloy/`. The origin contains no path.
Keep this file in the private backend alongside `server.py`. The application
uses these configured values rather than trusting proxy forwarding headers.
HTTP is supported only for an explicitly configured literal loopback origin
with a matching Host and loopback connection; use the same address in your
browser as in the command. Production requires HTTPS.

To rotate a password, repeat the setup command with `--replace`. An existing
configuration is never overwritten implicitly. Rotation invalidates previous
sessions and pending authority when the backend observes the new configuration.
Logout and expiry also prevent an old draft from being published.

## IIS permissions and upgrades

The current installer grants LOCAL SERVICE read access to backend code and
configuration, and Modify access to the private `exercises` directory so SQLite
can create its database journals. It must not have write access to Python/Java
code, `admin.local.json` or OpenAI credentials. Keep the backend outside every
IIS website's physical directory. Only the package's `wwwroot` is public.

For an existing installation, stop the backend, install the complete updated
package while preserving its database and private configuration, then run the
updated manager's `-Action UpdateDataPermissions` before restarting. Include the
installation's `-RuntimeRoot` and `-TaskName` if they differ from the defaults.
This migrates only the private exercise directory's ACL; restarting alone does
not update an older read-only ACL. Do not add writable permissions to the whole
backend. `Test-IisDeployment.ps1` checks the intended ACL scope on Windows;
the Linux test suite cannot establish actual NTFS permissions.

The three files in `wwwroot/admin` are the public shell only. Upload witnesses,
oracles, setup scripts, server modules, database/journal files and live
configuration are private. The installer accepts added exercises; readiness no
longer assumes the seed's count of 181.

## Cloudflare and browser sessions

Configure Cloudflare to bypass caching the application's `/api/admin/*` paths
(including a prefix such as `/alloy/api/admin/*`). Preserve `Set-Cookie` and
`Cache-Control: no-store, private`; never cache authenticated responses. Keep
the application's existing no-store asset policy and content-versioned URLs.
Use HTTPS to Cloudflare and correctly validated TLS from Cloudflare to IIS.
These edge settings require verification on the deployment itself.

Production cookies are host-only, Secure, HttpOnly and SameSite=Strict, with
Path=/ and the `__Host-` prefix. Their names bind the configured origin and
application path. Path names do not isolate applications sharing one browser
origin: all applications at the same scheme, host and port are trusted. Use
distinct origins for unrelated websites. An IIS box may serve other sites;
do not change their bindings, ACLs or global proxy settings for this feature.

## Upload, review and publish

The **Exercise library** tab edits the public title and question without changing
any Alloy code or source witnesses. Removing a question hides it from the live
library and retains its original private evidence. Edits and removal require the
current preview version; refresh after another administrator changes it.

The **Candidate cache** tab shows private drafts whose exact samples and bounded
disagreement checks agree with the primary oracle. Retention starts only after
administration is configured. The limits are 100 drafts total and 10 per exercise.
Select a draft to read its code and check evidence. **Review with Sol** makes one
explicit GPT-6.1 Sol High-effort request through the existing private OpenAI
configuration. Viewing candidates never makes a paid request.

Advice can recommend, reject, or remain uncertain; it cannot publish a solution.
Confirm **Approve** to run a fresh facts-aware Alloy check at scope at least five.
Only a successful, current check extends the correct pool. **Dismiss** records
the decision without adding anything. Missing AI configuration leaves manual
approval available. Agreements and counterexample ideas remain bounded evidence.
See the [full contract](admin-library-candidates-spec.md) for retention, scope,
stale decisions and private-data boundaries.

Open `/admin/` under your application path and log in. Upload one UTF-8 `.als`
file up to 256 KiB. Numbered solution predicates such as `inv1C0` and `inv1C1`
become one `inv1` exercise; standalone parameterless predicates keep their
names. The backend preserves original source and checks every solution with
Alloy. Unsupported dependencies or a nonequivalent late variant reject the
whole upload. See [the security contract](admin-security-spec.md) for naming,
source-preservation rules and bounded-equivalence limits.

Preparation and Luna suggestions run as bounded jobs, so the browser can poll
without keeping a request open for the full solver duration. Drafts remain in
memory, expire after 15 minutes and are visible only to their owning session.
Restarting the backend discards unpublished drafts.

Luna suggestions send the uploaded model and optional question seed to OpenAI
using the server's existing private key configuration. The page identifies
that transfer. Luna can suggest titles and questions; it cannot rewrite model
code or decide whether solutions are equivalent. If Luna is unavailable or no
key is configured, edit the questions manually. **Published titles and questions
are public**: review them for correctness and accidental solution disclosure.

Publish only after reviewing the preview. All selected exercise groups commit
together, duplicate IDs never overwrite existing exercises, and the running
portal receives one complete new snapshot. Existing exercise contents remain
unchanged. Host-side CLI additions still follow the private-administration
guide; authenticated browser publication is the path that refreshes the live
snapshot automatically.
