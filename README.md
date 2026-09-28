# Alloy Live Programming

A local web portal for practicing Alloy predicates using the ACGN / CanDis
canonical metric or raw AST Zhang–Shasha distance. It includes 181 exercises, live feedback, redacted edit
operations, the learner's canonical form, saved drafts, and GPT-6 Luna guidance.
Feedback uses the closest member of each bundled correct-predicate pool,
including the oracle, following the pool-ranking approach in `Alloy4FunAugmenter`.

**Alpha v0.0.1** (`v0.0.1-alpha`) adds a metric selector and a
[project CI/CD dashboard](docs/ci-cd.md) at `/dashboard/`. The
[Lean closure plan](docs/lean-closure.md) lists 24 open formal obligations and
their implementation requirements; it does not claim completed Lean proofs.

On Linux or macOS, install **Python 3.10+ and a JDK 17+** (including `javac`).
From a new clone, run:

```bash
./scripts/run.sh
```

The repository includes the exercise catalogue, correct-predicate pools, engine
sources, and seven dependency JARs. No IIS ZIP or original ACGN checkout is
required. To prepare and check the checkout without starting the server, use
`./scripts/setup.sh` without flags.

Open **http://127.0.0.1:8080**. Setup and startup validate the bundled JARs, compile
the engine, and run 378 engine checks. They need no Node, npm, pip packages, or
IIS. No frontend CDN or external font is needed. Stop with Ctrl+C. Options include
`--port 8081`, `--timeout 12`, `--workers 4`, and `--java-home /path/to/jdk`.
See [Linux and macOS setup](docs/local-setup.md) for installation, private config,
and troubleshooting. The separate developer/release build uses Node for its
JavaScript syntax check.

Choose an exercise, edit its predicate body, and pause to receive feedback.
Select **Canonical form** or **Raw syntax tree (AST)** above the editor.
Each mode independently chooses its nearest known correct predicate, including
the oracle. Switching clears stale feedback and guidance and keeps separate
distance histories. Luna explains each mode's individual edit operations using
only learner context and approved operator hints.
Ctrl/Cmd+Enter checks immediately. The complete surrounding Alloy environment
is available beside the editor. Download exports that environment with your
current predicate. Drafts and recent distance history stay in your browser.
After a successful canonical check, the panel below the editor shows your compiled
predicate's canonical form with compact display whitespace. Click an edit step
to color the canonical expression selected by that edit and underline its
original source occurrence when retained. When only related context is
available, the locator labels it explicitly. It reports both predicate-body
and complete-model line and column positions. If several source expressions
remain possible, choose one to inspect; editing the draft clears the highlights
until the next check.
Behavioral feedback loads separately below the editor and feedback panels. It
shows the **behavioral similarity score** to three decimal places and up to three
instances in each oracle/student category: both accept, undercoverage (only the
oracle accepts), overcoverage (only your predicate accepts), and neither accepts.
Select an example to inspect its atoms and relations; temporal instances also
let you select a state. Empty categories mean no instance exists **within the
displayed bounds**. Changing the draft clears the previous behavioral results.
All 181 exercises have natural-language requirements displayed above the editor.
Read them together in [the exercise guide](docs/exercise-descriptions.md).
The descriptions state the task in prose while the browser hides reference
predicate implementations. They account for each exercise's own declarations
and facts, without assuming that other numbered invariants hold.

Descriptions live in `scripts/exercise_descriptions.json`, bound to the original
model's SHA-256 so a changed source cannot silently inherit an outdated task.
Fresh imports apply matching descriptions automatically. To update an existing
catalogue without the original ACGN checkout, run:

```bash
python3 scripts/import_exercises.py --refresh-descriptions --guide docs/exercise-descriptions.md
```

Restart the backend after refreshing. This command changes only description
metadata; it preserves the Alloy environment, starter, reference, and correct
candidate pools. The IIS ZIP includes the updated descriptions and helper data.

## Environment and reference isolation

The catalogue importer preserves the original UTF-8 bytes of signatures, fields,
facts, imports, helpers, and other context. It removes the oracle predicate and
the exact grading harness that references it. Predicate body editing cannot add
top-level declarations or replace the context. Every retained and removed span
is recorded against its source hash. All 181 original selected files are
preserved in the bundled catalogue for reproduction.

The server reads `exercises/catalogue.json` and exposes an explicit
public field projection. Only the named portal assets and four dashboard files are served. It parses the
learner and oracle in separate JVM modules so the learner cannot call the
oracle. Reference bodies, canonical forms, target expressions, private predicate
and variable names, raw exceptions, and credentials are not sent to the browser.
Behavioral instances expose concrete model atoms and relations, including integer
values; String contents use opaque identities so private string literals stay hidden.
Replacement operator names are explicitly permitted hints. Costs, operator hints,
and redacted operation categories deliberately reveal repair information; repeated queries
can help infer a solution. This is a learning interface, not a secrecy guarantee
against someone reading this public repository or the original corpus.

`exercises/catalogue.json` and `exercises/correct-pools.json` are tracked public
source data. Anyone reading or cloning this repository can inspect their oracle
and candidate bodies. Browser redaction keeps solutions out of the learning
interface; it does not make the repository data secret. The bundled pair contains
181 exercises and 7,731 candidates: 7,550 deduplicated corpus candidates plus 181
oracles. See [exercise data and provenance](exercises/README.md) for the source
witnesses and validation boundary. Credentials remain private, ignored by Git,
and excluded from release archives.

For optional imports from a different ACGN corpus, inspect:

```bash
python3 scripts/import_exercises.py --help
python3 scripts/import_correct_pools.py --help
```

## Distance and edit interpretation

Each comparison set contains the exercise's oracle and corpus submissions
labelled `correct` whose surrounding environment matches the preserved exercise
byte for byte. The importer keeps source hashes and bundled source witnesses,
deduplicates correct bodies by conservative code-token identity, and retains one
explicit oracle. It excludes submissions from different environments: a correct
label in a changed context does not establish correctness in this exercise.
Every request prepares the learner once, compares **every** admitted candidate,
and builds the trace against a minimum-distance candidate. Ties use deterministic
pool order. An invalid candidate or timeout produces an error, never a
partial minimum or an oracle-only fallback. Exact matches to correct candidates
return zero, including alternative formulations with positive oracle distance.

As in `Alloy4FunAugmenter`, correctness comes from corpus classifications and the
oracle. This is minimum distance over a finite known-correct pool, not synthesis
over every semantically correct Alloy predicate or a new solver proof. Unlike
the augmenter's experiment on incorrect submissions, live feedback retains
exact learner/candidate matches. Pools without compatible correct submissions
contain only their oracle; the interface reports that limitation. Candidate
bodies, identities, sources, and the selected target are not sent to the browser.

The Java adapter calls the bundled framework's `Canonical.prepare`,
`Canonical.distanceBreakdown`, `Canonical.irTemporalFol`, and `Canonical.edits`.
The default metric is the **Fast Rewrite IR canonical distance**, split into
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

Canonical locators follow the edit trace's recorded child path to the selected
rendered node. Equal text in another branch does not select that other occurrence.
Source locators use the selected node's parser origin when normalization retains
it. These locations are labelled **Expression selected by this edit**. Normalization
can merge or remove source identity; those cases show explicitly labelled related
context, possible source locations, or an unavailable location. Temporal or grouped
edits can show whole-form context instead of one node. Node identity is not a proof
of a defect or an executable source patch. Both views use the same color for the
selected edit step.
Whitespace compaction preserves quoted literal contents and does not alter the
metric. Location text is derived from this learner draft and canonical form;
reference solutions and credentials are never used to construct these locators.

Raw AST mode uses ACGN's ordered-tree Zhang–Shasha implementation with the
framework's raw AST labels and child order. Each unit step inserts, deletes or
relabels one node; deletion promotes children and insertion can wrap consecutive
children. The engine independently backtracks and privately replays the script.
It retains parser `Body`/`NOOP` wrappers and variable spellings, so these distances
can differ from intuitive source-token counts and from the historical augmenter's
subtree-based raw-AST recurrence. No canonical normalization runs in AST mode.
Only original-code locations are shown for AST edits; the canonical panel stays
hidden. Limits and the response contract are in [the engine guide](engine/README.md).

## Behavioral similarity and examples

Behavioral comparisons always use the original oracle. Both edit-distance modes use the closest member of the correct pool. `live.BehaviorFeedback` adapts
ACGN's `Rewarder` formula, with **model facts enforced** for both sampling and
category searches. This intentionally fixes the upstream Rewarder's omission of
module-level facts. This facts fix lives in the portal's behavior worker.

The solver uses SAT4J, an overall scope of 3, 3-bit integers, maximum sequence
length 3, temporal traces of 1–10 states, and up to 100 samples of each oracle
polarity. These are ACGN's fixed bounds, not the scopes of source run/check commands.
If `P` oracle-positive samples contain `p` learner acceptances and `N`
oracle-negative samples contain `n` learner rejections, the reward is
`(p * n) / (P * N + c)`. The correction `c` is zero unless every sample agrees;
then it counts the satisfiable undercoverage and overcoverage directions (0–2).
The displayed value is rounded to the nearest 0.001. Rounding can display `1.000`
even when a rare counterexample exists; inspect the categories as well.

The four categories are solved independently, so a sampled pool missing a case
does not label that category empty. Each category exposes at most three examples,
and says whether enumeration finished. Atom/relation output is bounded, with
truncation labeled. Temporal instances include state and loop information. String
values are anonymized consistently within an instance; signatures, field names,
and other atom identities come from the public model. No solver command, private
source, XML metadata, or skolem bindings are returned. Only this public instance
projection is available to Luna for explaining the displayed examples.

If model facts make either oracle polarity unsatisfiable within these bounds,
ACGN's sampled reward is undefined: the score is unavailable while the categories
remain usable. A timeout or unsupported form is also distinct from UNSAT. Models
whose facts or shared helpers depend on the edited predicate are rejected by this
adapter rather than silently evaluated against a different environment.
Learner-only String literals outside the oracle's sampling universe are also
reported as unsupported, rather than silently changing the sampling universe.

`POST /api/behavior` accepts the same exercise/body/revision fields as feedback.
It uses a separate single-worker slot, a cache bound to the exercise and exact
draft, and a process timeout of at least 30 seconds (`max(30, --timeout)`).
Canonical checks remain independent. Scores are bounded sample measurements,
not probabilities of correctness or unrestricted equivalence proofs.

## GPT-6 Luna

The server uses the [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna)
through the [Responses API](https://developers.openai.com/api/reference/typescript/resources/beta/subresources/responses/methods/create).
Guidance is attached to individual edits and examples, followed by a short learning
summary. Each operation receives a novice-friendly explanation using the learner's
raw predicate body, compact canonical form, and validated node or context locators.
Each of the displayed instances (up to three in each of four categories) receives
its own explanation using its public atoms, relations, truth category, and temporal
states. Empty categories do not receive invented examples.

Luna is instructed to explain concepts and suggest what to inspect, without giving
a repaired predicate, hidden replacement expression, or complete repair route.
Permitted replacement-operator hints remain available. The server sends a positive
allowlist: it never sends the oracle body, correct-pool bodies, target expressions,
full module/environment, solver commands, or private metadata. The model sees only
the learner's code and public feedback. AI guidance cannot change distances,
scores, category membership, or instance tuples.

Responses use `store: false` and
[strict structured output](https://developers.openai.com/api/docs/guides/structured-outputs).
The server verifies that every operation and instance has exactly one description
with the correct ID; missing, duplicated, or unexpected IDs are rejected. Each
description is at most 360 characters; the summary is at most 700. Requests exceeding
128 operations, 600 characters in an operation detail field, 64 KiB of canonical
text, or 128 KiB of serialized context return
an explicit unavailable result rather than silently omitting items. Obvious code
solutions and fenced code are rejected; this is a bounded output check, not a proof
about all possible AI wording.

The browser requests explanations after the behavioral check completes. A
`behaviorToken` binds descriptions to the exact cached examples, exercise, and
draft. Expired snapshots require a fresh check. When behavior is unavailable,
operation guidance still works and the summary acknowledges the missing evidence.
Editing or switching exercises clears old guidance; all AI text is rendered as
plain text. Deterministic results remain available if Luna is disabled or fails.

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

The UI handles quota failures while preserving canonical feedback. No different
model is substituted. Run `python3 scripts/check_luna.py` to check current access
with one learner draft, its redacted trace, and its public behavioral examples.
Live service availability and nondeterministic explanation content remain separate
from the offline mechanical closure.

## IIS 10.0 deployment

Build a Windows deployment archive directly from a fresh source checkout. The
tracked catalogue and correct-predicate pools contain all required exercise
data; no original ACGN checkout or existing IIS archive is needed. From the
checkout root on Windows, one command builds and packages the portal:

```powershell
powershell -NoProfile -File scripts/build.ps1 -RequireNode
```

On Linux, macOS, or Windows Git Bash, use:

```bash
./scripts/build.sh
```

Both portal build commands refresh `build/iis/alloy-studio-iis.zip` and
`build/iis/alloy-studio-iis.zip.sha256` after successful checks. A separate
packaging command is no longer required. The build needs Python 3.10+, a JDK
17+ with `javac`, and Node for the frontend syntax check (`-RequireNode` makes
that check mandatory in PowerShell).

The portal build validates its inputs and frontend, then packages freshly
compiled Java classes. Compilation is shared through `scripts/build_engine.py`.
If compilation, frontend validation, or package preflight fails, the command
fails without reporting an updated package. Any existing ZIP and checksum
remain the **older successful build**; they do not contain the failed changes.

The packaged `wwwroot/index.html` references `app.js` and `styles.css` with
SHA-256 query versions derived from their exact bytes. This changes the asset
URLs whenever their content changes; source `web/index.html` stays unchanged.
The IIS settings configure `Cache-Control: no-cache, no-store`, disable
static/output caching, and suppress static ETags; verify the effective headers
on the target host. Existing browser or Cloudflare entries still need clearing
during the first upgrade. ZIP entry
timestamps are fixed for reproducibility, so an extraction date is not evidence
that an installed file changed. Follow the [installed-site update steps](deploy/iis/README.md#updating-an-existing-installation),
including refreshing public-file timestamps, restarting the backend, and purging
only this website's cached URLs. Building or extracting a ZIP does not update
the directories already used by IIS and its scheduled task.

For packaging alone, `python3 scripts/package_iis.py` also compiles Java from
source before creating the archive, so it needs a JDK and does not reuse old
classes. Node syntax checking belongs to the portal build commands above.
The packaging CLI accepts `--javac` for a compiler, `--classes-output` for the
compiled-class directory, `--source` for another checkout, and `--output` for
a different private ZIP destination. For example:

```bash
python3 scripts/package_iis.py --source /path/to/checkout \
  --javac /path/to/jdk/bin/javac --classes-output /path/to/classes \
  --output /private/alloy-studio-iis.zip
```

PowerShell's `-JavaCompiler` and `-Python` selections are forwarded through
compilation and packaging; `-OutputDirectory` selects the compiled-class
directory. Choose a dedicated directory containing only compiled classes;
protected project directories and directories with unrelated files are refused.
Use `-EngineOnly` to compile without updating the archive. Direct
`./engine/build.sh` also compiles only the engine, defaulting to the same
`build/engine/classes` directory under the project root.

If a bundled data file is missing or damaged, preserve any intentional local
data edits, then restore the matching pair from Git:

```bash
git restore --source=HEAD -- exercises/catalogue.json exercises/correct-pools.json
```

For a custom import or a legacy checkout without tracked exercise data, the
original corpus must contain `classified-data/`. Pass its location with
`.\scripts\build.ps1 -ACGNRoot C:\path\to\ACGN` on PowerShell, or set `ACGN_ROOT`
before `./scripts/build.sh` on Bash. A trusted IIS ZIP is also an optional
recovery source:

```bash
python scripts/prepare_private_data.py --from-bundle /path/to/alloy-studio-iis.zip
```

Use `python3` if that is the Python 3.10+ command on the machine, then rerun the
normal build command. This legacy-named helper verifies the manifest hashes,
catalogue source records, and correct-pool witnesses before restoring exactly
`exercises/catalogue.json` and `exercises/correct-pools.json`. It copies no API
keys or application files. Once that pair exists, validation needs neither the
original ACGN checkout nor the ZIP. A valid existing pair is left unchanged;
back up and move both files first when intentionally importing a different
corpus. Partial or invalid pairs are reported and preserved for recovery.

Preparation failures now have specific codes: `SOURCE_CORPUS_MISSING` names the
searched `classified-data` path; `EMPTY_CORPUS` identifies a directory with no
usable exercise groups; `PARTIAL_PRIVATE_DATA` names the missing file;
`PRIVATE_DATA_INVALID` identifies invalid existing data; and `BUNDLE_INVALID`
identifies an incomplete or inconsistent ZIP. Diagnostic output omits
predicate contents. `vendor/acgn` supplies framework dependencies, not the
original exercise corpus; the tracked exercise pair supplies the data needed
for this portal.

The output is `build/iis/alloy-studio-iis.zip` with a SHA-256 checksum. Follow
[the IIS deployment guide](deploy/iis/README.md) for prerequisites, site or virtual
application setup, HTTPS, startup task installation, and target-side acceptance.
Only the archive's `wwwroot` directory becomes an IIS physical directory. The
server-only `backend` directory contains the preserved exercise catalogue,
correct pools, compiled engine, and runtime JARs; keep it outside the public IIS
directory. Credentials are excluded from the archive. Supply your own key in
the private JSON configuration;
the masked Windows key setup script also supports a separate private key file.

IIS serves static assets and proxies `/api` to `127.0.0.1:8080` using URL Rewrite
and Application Request Routing. A Windows Scheduled Task runs the Python
backend as LOCAL SERVICE at startup and restarts it after a failure. Windows
needs Python 3.10+ and Java 17+; Node and a JDK are unnecessary for running the
precompiled package. Build scripts, including `scripts/build_engine.py`, stay
in the source checkout and are not included in the runtime ZIP. Rebuilding
from source on Windows uses `scripts/build.ps1`.
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
powershell -NoProfile -File scripts/build.ps1 -RequireNode
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
dependency paths.` If that line is absent on Git Bash, update the complete
checkout, including both Bash entry points, `scripts/build-windows.sh`,
`scripts/build.ps1`, `scripts/build_engine.py`, and `scripts/package_iis.py`.
Linux and WSL using Linux Java keep the POSIX build path. A custom output path
remains relative to the project root for `scripts/build.sh`, and to the caller's
directory for `engine/build.sh`.

The IIS ZIP is precompiled, so deployment needs Java 17+ and Python 3.10+ and
does not require a source build. From the extracted distribution root:

```powershell
python .\backend\runtime_dependencies.py --java java
```

This prints a JSON report with a result and SHA-256 for **each** dependency and
runs 378 compiled engine checks in a fresh JVM. IIS Install, Start, Restart and
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
on Windows/IIS or macOS, and unrestricted semantic equivalence are outside its claim
boundary. Local tests cover the packaged runtime, proxy paths, configuration,
and Windows compatibility branches. The Windows acceptance script checks the
installed target separately.

The portal defaults to loopback and has bounded worker count, heap, request size,
and execution time. For shared hosting, use a separately configured authenticated
HTTPS reverse proxy; access control and multi-tenant deployment are outside this
local portal's verification surface.

ACGN base snapshot: `1e2667351532b0c632166fa21ae5fbc7308a8fe7`.
The vendored copy includes a local presentation metadata patch that carries parser
origins through canonicalization for the locators. `vendor/acgn/snapshot.json`
records the delivered file hashes; this copy is not byte-identical to the base
snapshot. The upstream checkout remains unchanged. See `vendor/acgn/LICENSE` for
its license.
