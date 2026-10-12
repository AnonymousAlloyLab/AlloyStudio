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
become one `inv1` exercise; standalone predicates keep their
names. The backend preserves original source and checks every solution with
Alloy. The selection step shows up to 100 predicate groups, with up to 256
solution declarations in total. No practice is preselected; leave control and
frame-condition predicates used by transactions as helpers. Select which groups become practices, then click
**Validate selected predicates**. Numbered variants stay together; they cannot be
selected individually. Unselected predicates and their original bodies remain
in the model environment shown to learners. The full original upload must parse;
every original numbered InvXC group must agree, including unselected groups.
Every selected solution also passes bounded equivalence in its retained exercise
environment. Unsupported dependencies or a nonequivalent late variant reject the
whole preparation. Failed selection validation keeps the file and checkboxes
locally, so change the selection and retry without parsing again. The rejected
backend draft is discarded and cannot publish. See [the security contract](admin-security-spec.md) for naming,
source-preservation rules and bounded-equivalence limits.

Explicit parameters are supported, for example `pred reachable[n: Node]` or
`pred connected[disj a, b: Node, links: set Node->Node]`. Exact-bound declarations
such as `pred allNodes[n = Node]` are supported too. The editor displays the
fixed typed header above the editable body. A practice may have up to 16 argument
names, relation arity up to eight, and an 8-KiB header. All numbered alternatives
and an optional bare starter must use matching parameter declarations. Convert
receiver-style syntax such as `pred Node.rule` to an explicitly parameterized
predicate before selecting it as a practice; otherwise it stays a helper.
Unselected parameterized helpers may use the model-wide source budget; selecting
one applies the practice body and header limits. Discovery omits oversized helper
headers rather than displaying their contents.

Retained aggregators such as `pred invariants { inv_a and inv_b }` may call
practice slots. Other selected standalone answers are replaced with named blank
TODO slots in each exercise, so their bodies stay hidden and aggregates still
parse. Uploaded assertions and commands are displayed unchanged; live feedback
uses its own queries rather than running those commands. A practice answer may
call another selected practice in new version-three uploads. The backend derives
the prerequisite graph from actual Alloy calls, including retained helpers.
Downstream practices stay locked until their prerequisites pass both structural
and behavioral checks. Their environment uses the learner's last verified
prerequisite code. Required numbered aliases such as `inv1C0[n]` become typed
forwarding wrappers to `inv1[n]`, without revealing the hidden answer. Cycles and
dependencies from facts or declaration domains remain rejected. Existing
version-one and version-two archives retain their original rules.

The complete private upload and unselected declarations stay unchanged. Private
publication validation uses the author's correct prerequisite implementations;
learner feedback compares student and reference bodies in the same verified
learner context. An active prerequisite cannot be removed until its dependents
are removed. See the [dependency contract](practice-dependencies-progress-spec.md)
and [learner progress guide](progress-save.md).

Parameterized equivalence checks both bodies for the same legal argument values.
Behavioral examples show those values beside the diagram; the score samples
model-and-argument assignments. Counterexamples in either direction prevent a
score of 1.000. These are bounded checks, not an unbounded equivalence proof.

Preparation and Luna suggestions run as bounded jobs, so the browser can poll
without keeping a request open for the full solver duration. Drafts remain in
memory, expire after 15 minutes and are visible only to their owning session.
Restarting the backend discards unpublished drafts.
Discovery starts one inspection JVM. Selected preparation starts one inspector
and one sequential validation JVM, independent of the number selected. These
JVMs run one at a time, requesting two processors and a 256-MiB heap. The
backend's shared runtime profile controls the effective processor count.
Preparation has a 60-second total deadline; a difficult model can still
time out and remains unpublished. Large repeated environments may require a
smaller selection: prepared data is bounded to 8 MiB per draft and 32 MiB across
retained drafts. Discard an earlier draft to free its budget.

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
