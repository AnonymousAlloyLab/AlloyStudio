# Alloy Live Programming

A local web portal for practicing Alloy predicates using the ACGN / CanDis
canonical repair metric. It includes 181 exercises, live feedback, redacted edit
operations, the learner's canonical form, saved drafts, and GPT-6 Luna guidance.
Feedback uses the closest member of each private correct-predicate pool,
including the oracle, following the pool-ranking approach in `Alloy4FunAugmenter`.

Run from this directory:

```bash
./scripts/run.sh
```

Open **http://127.0.0.1:8080**. Java 17+, Python 3.10+, and Node 20+ are installed
in this environment. The Java framework sources and dependency JARs are bundled
under `vendor/acgn`; runtime does not depend on `/home/augustus/ACGN` or npm.
The build script uses Node for a JavaScript syntax check. No frontend CDN or
external font is needed. Stop the server with Ctrl+C. Options include
`--port 8081`, `--timeout 12`, and `--workers 4`.

Choose an exercise, edit its predicate body, and pause to receive feedback.
Ctrl/Cmd+Enter checks immediately. The complete surrounding Alloy environment
is available beside the editor. Download exports that environment with your
current predicate. Drafts and recent distance history stay in your browser.
The eight graph exercises have reviewed natural-language requirements; the
other corpus groups identify their original invariant because their source
does not include a task statement.

## Environment and reference isolation

The catalogue importer preserves the original UTF-8 bytes of signatures, fields,
facts, imports, helpers, and other context. It removes the oracle predicate and
the exact grading harness that references it. Predicate body editing cannot add
top-level declarations or replace the context. Every retained and removed span
is recorded against its source hash. All 181 original selected files are
preserved in the private catalogue for reproduction.

The server reads `exercises/catalogue.json` privately and exposes an explicit
public field projection. Only three named web assets are served. It parses the
learner and oracle in separate JVM modules so the learner cannot call the
oracle. Reference bodies, canonical forms, target expressions, target-only names
and constants, raw exceptions, and credentials are not sent to the browser.
Replacement operator names are explicitly permitted hints. Costs, operator hints,
and redacted operation categories deliberately reveal repair information; repeated queries
can help infer a solution. This is a learning interface, not a secrecy guarantee
against a user with server filesystem access or the original public corpus.

Keep the private catalogue, correct pools, and credentials out of a public repository.
The catalogue and pools are excluded by `.gitignore` but included in this delivered local
environment. Recreate it from an ACGN checkout with:

```bash
python3 scripts/import_exercises.py --help
python3 scripts/import_correct_pools.py --help
```

## Distance and edit interpretation

Each comparison set contains the exercise's oracle and corpus submissions
labelled `correct` whose surrounding environment matches the preserved exercise
byte for byte. The importer keeps source hashes and private source witnesses,
deduplicates correct bodies by conservative code-token identity, and retains one
explicit oracle. It excludes submissions from different environments: a correct
label in a changed context does not establish correctness in this exercise.
Every request prepares the learner once, compares **every** admitted candidate,
and builds the trace against a minimum-distance candidate. Ties use deterministic
private pool order. An invalid candidate or timeout produces an error, never a
partial minimum or an oracle-only fallback. Exact matches to correct candidates
return zero, including alternative formulations with positive oracle distance.

As in `Alloy4FunAugmenter`, correctness comes from corpus classifications and the
oracle. This is minimum distance over a finite known-correct pool, not synthesis
over every semantically correct Alloy predicate or a new solver proof. Unlike
the augmenter's experiment on incorrect submissions, live feedback retains
exact learner/candidate matches. Pools without compatible correct submissions
contain only their oracle; the interface reports that limitation. Candidate
bodies, identities, sources, and the selected target remain private.

The Java adapter calls the bundled framework's `Canonical.prepare`,
`Canonical.distanceBreakdown`, `Canonical.irTemporalFol`, and `Canonical.edits`.
The selected metric is the **Fast Rewrite IR canonical distance**, split into
temporal, quantifier, and matrix costs. This is not the certificate-integrated
quotient path. Zero means equality under the implemented canonical rewrite
theory; it is not an unrestricted Alloy semantic proof.

The portal reconstructs matrix operations using the metric's coherent variable
alignment, ordered child edit calculation, and unordered child assignment. It
checks their cost against ACGN and privately replays the matrix plan before
publishing its redacted projection. Each matrix operation shows the affected
learner fragment (or insertion anchor), current operator, an allowed replacement
operator when applicable, and a concrete next step. Internal paths are secondary
details. Target expressions, inserted operands, and replacement names/constants
remain hidden.

Quantifier operations are reconstructed from the binding edit calculation and
checked against its component cost. Temporal hints still use the framework's
readable trace; a temporal component whose hints disagree with its minimum cost
is clearly marked as a `component-edit` aggregate. Displayed costs sum to the canonical distance.
Matrix replay checks a canonical-tree plan to the selected nearest candidate, not an automatically applicable
source patch or a formal semantic proof. Normalization can change the shape of
the learner's expression, so canonical locations are not claimed as source lines.
The interface never automatically applies an unverified textual edit.

## GPT-6 Luna

The server uses the [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna)
through the [Responses API](https://developers.openai.com/api/reference/typescript/resources/beta/subresources/responses/methods/create).
It sends a positive allowlist of completed pool-comparison counts, numeric components, operation kinds/costs,
affected learner canonical fragments, action guidance, and permitted replacement
operators. It sends no full learner module, oracle source, environment, target
expression, or target-only name/constant. Detail is bounded to 32 operations and
600 characters per field, with truncation reported. Responses use `store: false`. AI guidance is
separate from the deterministic feedback and cannot change the reported metric.

Supply your own key once per deployment in a **private configuration file**.
Copy `openai.example.json` to `openai.local.json` beside `luna.py` (inside
`backend` in the IIS package), then edit its `api_key` value locally:

```json
{
  "api_key": "YOUR_OPENAI_API_KEY"
}
```

On Linux/macOS, create the private copy with `install -m 600 openai.example.json
openai.local.json` before editing it. On IIS, follow the deployment guide to apply
the private backend ACLs and restart the backend after adding the configuration.
The file belongs outside `wwwroot`. There is no portal key entry. The server
reads the credential, sends it only in OpenAI's authorization header, and redacts
credential-shaped text from explanations and provider errors.

Instead of storing the key directly, a configuration can contain
`{"api_key_file": "secrets/openai.key"}`. That path is relative to the configuration
file, so the backend and its private folder can move together. Use either
`api_key` or `api_key_file`. Invalid or unreadable configuration disables Luna
without exposing its contents; deterministic feedback remains available.

The loader checks, in order: `OPENAI_API_KEY`, `OPENAI_CONFIG_FILE`,
`OPENAI_API_KEY_FILE`, `openai.local.json` beside `luna.py`, then
`secrets/openai.key` beside `luna.py`. Environment-specified relative paths start
at that backend directory, independent of the working directory. There is no
implicit home-directory lookup. An explicitly selected but invalid credential
file does not fall back to another key. `.env.example` documents environment
overrides; it is not automatically loaded. `OPENAI_DISABLED=1 ./scripts/run.sh`
runs offline. Key changes take effect on the next explanation request; cached
explanations are separated by a hash of the credential.

`openai.local.json` is ignored by Git and excluded from distribution archives
and closure snapshots. The archive ships only the checked, blank
`openai.example.json`. Private configuration uses filesystem access controls,
not encryption; server administrators can read it. Keep custom credential files
outside the source tree or inside the ignored `secrets` directory.

The end-to-end live check on September 25, 2026 succeeded: `/api/explain`
computed a real repair trace and received a GPT-6 Luna explanation. An earlier
request returned HTTP 429 `credit_balance_exhausted`; that transient account
condition no longer blocked the later check. The UI handles quota failures while
preserving canonical feedback. No different model is substituted.
Run `python3 scripts/check_luna.py` to check current access with one redacted trace.
Live service availability and nondeterministic explanation content remain separate
from the offline mechanical closure.

## IIS 10.0 deployment

Build a Windows deployment archive from this complete local environment. The
catalogue and correct-predicate pools are private, ignored Git files. A fresh
source checkout creates them from the original sibling `ACGN/classified-data`
checkout automatically; if it is elsewhere, pass its path explicitly:

```bash
./scripts/build.sh
python3 scripts/package_iis.py
```

On Windows PowerShell, use `.\scripts\build.ps1 -ACGNRoot C:\path\to\ACGN`
when the original ACGN checkout is not the sibling `ACGN` directory. Git Bash
can set `ACGN_ROOT` before `./scripts/build.sh`. The original checkout must
contain `classified-data/`. Without it, restore **both** ignored files from a
trusted private bundle; a public source checkout does not contain enough data
to recreate this corpus. `scripts/package_iis.py` checks the inputs and explains
this prerequisite if either file is absent. The already-built private IIS ZIP
contains both files and can be deployed without rebuilding the source.

The output is `build/iis/alloy-studio-iis.zip` with a SHA-256 checksum. Follow
[the IIS deployment guide](deploy/iis/README.md) for prerequisites, site or virtual
application setup, HTTPS, startup task installation, and target-side acceptance.
Only the archive's `wwwroot` directory becomes an IIS physical directory. The
private `backend` directory contains the preserved exercise catalogue, correct pools, compiled
engine, and runtime JARs; keep the archive and backend private. Credentials are
excluded from the archive. Supply your own key in the private JSON configuration;
the masked Windows key setup script also supports a separate private key file.

IIS serves static assets and proxies `/api` to `127.0.0.1:8080` using URL Rewrite
and Application Request Routing. A Windows Scheduled Task runs the Python
backend as LOCAL SERVICE at startup and restarts it after a failure. Windows
needs Python 3.10+ and Java 17+; Node and a JDK are unnecessary for running the
precompiled package. Rebuilding from source on Windows uses `scripts/build.ps1`.
The frontend supports both a site root and a virtual application such as `/alloy/`.
After installation, use `deploy/iis/Start-AlloyStudio.ps1 -PublicUrl https://alloy.example.org/`
in an elevated Windows PowerShell session to start IIS and the backend together.
The backend's repeatable `--public-origin` option accepts explicit browser origins
behind the proxy; it does not trust forwarded headers as authorization.

## Dependency and portability checks

The complete source checkout and IIS archive include all seven pinned JARs:
`AlloyASG-Release.jar`, `AlloyASG.jar`, `AlloyParser.jar`, `alloy.jar`,
`commons-cli-1.4.jar`, `json-java.jar`, and `slf4j-simple-1.7.36.jar`.
Their directory is `vendor/acgn/lib` in the source checkout, and
`backend/vendor/acgn/lib` in the IIS archive. No Maven cache, upstream ACGN
checkout, or separately installed AlloyASG library is required.

For a Windows source build, run from the checkout root:

```powershell
python .\runtime_dependencies.py --dependencies-only
.\scripts\build.ps1
python .\runtime_dependencies.py --java java
```

The build checks every JAR against its snapshot SHA-256 before invoking `javac`,
then passes all seven absolute JAR paths as one classpath argument using the
operating system's separator. A missing or changed JAR stops the build with its
filename. Errors such as `package edu.mit.csail.sdg.alloy4 does not exist`,
`package org.json does not exist`, or `package is.fivefivefive.alloyasg.asg does
not exist` indicate that the compiler cannot use the bundled classpath; copying
only the Java source directories is insufficient.

When using **Git Bash, MSYS2, or Cygwin on Windows**, the original command also
works:

```bash
./scripts/build.sh
```

Both Bash build entry points detect those Windows shells and invoke
`scripts/build.ps1`. The bridge converts the script and output directory with
`cygpath`, disables a second MSYS argument conversion, and lets native PowerShell
construct Windows paths and semicolon-separated Java classpaths. The first line
is `Windows Bash detected: building through scripts/build.ps1 with native Windows
dependency paths.` If that line is absent on Git Bash, update **both** Bash entry
points, `scripts/build-windows.sh`, and `scripts/build.ps1` from this checkout.
Linux and WSL using Linux Java keep the POSIX build path. A custom output path
remains relative to the project root for `scripts/build.sh`, and to the caller's
directory for `engine/build.sh`.

The IIS ZIP is precompiled, so deployment needs Java 17+ and Python 3.10+ and
does not require a source build. From the extracted distribution root:

```powershell
python .\backend\runtime_dependencies.py --java java
```

This prints a JSON report with a result and SHA-256 for **each** dependency and
runs 372 compiled engine checks in a fresh JVM. IIS Install, Start, Restart and
the target acceptance script run the same check. Missing, changed, extra, or
linked JARs fail the check before the backend starts. `AlloyASG.jar` contains
source files; its compiled classes come from `AlloyASG-Release.jar`. Several
other JARs overlap with that release JAR, so successful feedback alone cannot
establish that the entire dependency set was copied.

The offline portability tests extract the package into a moved directory with
spaces, verify it without the original checkout or ambient Java/Python paths,
and remove or corrupt each JAR in turn to confirm rejection. A separate fresh
source build exercises the explicit classpath. Windows-specific execution still
requires the supplied acceptance script on the target host.

## Checks and closure

```bash
./scripts/build.sh
OPENAI_DISABLED=1 python3 -m unittest discover -s tests -v
npm ci
npx playwright install chromium   # only if Chromium is not already installed
node tests/browser.mjs
python3 scripts/verify_closure.py --help
```

The closure package records a finite claim set, hashed inputs/verifiers,
declared trusted dependencies, two isolated clean builds, reproducible class,
web, and IIS archive hashes, and bound evidence. See `closure/README.md` and the
machine-readable run report for the actual state. AI prose correctness, live
OpenAI availability, universal parser/normalizer correctness, actual execution
on Windows/IIS, and unrestricted semantic equivalence are outside its claim
boundary. Local tests cover the packaged runtime, proxy paths, configuration,
and Windows compatibility branches. The Windows acceptance script checks the
installed target separately.

The portal defaults to loopback and has bounded worker count, heap, request size,
and execution time. For shared hosting, use a separately configured authenticated
HTTPS reverse proxy; access control and multi-tenant deployment are outside this
local portal's verification surface.

ACGN source snapshot: `1e2667351532b0c632166fa21ae5fbc7308a8fe7`.
The upstream checkout remains unchanged. See `vendor/acgn/LICENSE` for its license.
