# Constrained runtime implementation and validation

The [v0.0.5 contract](constrained-runtime-spec.md) reduces contention on a small
host and separates cold startup from analysis time. It retains Canonical as the
default, raw AST hints, the complete correct pools, behavioral scores, four
instance categories and their diagrams, Luna guidance, uploads, SQLite,
navigation and the dashboard.

## What changed

The default `constrained` execution profile runs one feedback worker and one
behavior worker, each with a 256 MiB maximum Java heap and one advertised JVM
processor. The administrator process lane remains available. Heap settings are
not total-process memory limits. The optional `standard` profile uses two
feedback workers and two advertised processors. Explicit worker overrides are
preserved; changing concurrency does not select another algorithm or shrink a
correct-predicate pool.

Worker acquisition/startup gets 20 seconds under the constrained profile, then
feedback receives its full configured execution allowance (12 seconds by
default). Behavior retains 30 seconds. Standard startup defaults to 10 seconds.
A queued request expires at its own queue deadline even when an earlier job is
still running. Capacity waits before spawning do not increment the broken-start
circuit. Request cleanup waits at most 250 ms and retains the process reservation
until reaping is confirmed. It does not declare an uncertain process dead.

Feedback receives a private cooperative work allowance equal to two thirds of
its remaining execution allowance, capped at 60 seconds. Charged work samples
the monotonic clock every 1,024 calls and checks it again before publishing a
result. Expiry returns `WORK_LIMIT` without publishing a partial nearest match;
the JVM remains available for the next edit. Parser/native calls and OS stalls
remain subject to the independent hard worker timeout. Legacy worker frames
still work. One-shot mode remains available and receives the same profile and
work allowance from the portal.

The default browser request limit is 150 seconds; IIS ARR is configured to at
least 120 seconds while preserving an existing higher global value. Those
limits accommodate the configured startup and legacy explanation allowances.
The provider's 40-second socket timeout is not a whole-operation deadline, so
the nominal 111.25-second maximum-settings explanation envelope is not an
absolute completion guarantee. No real provider calls were part of validation.

## Finite two- and four-core comparison

`scripts/benchmark_constrained_runtime.py` freezes source, binaries and request
identities before applying actual Linux CPU affinity in separate child runs.
Each affinity setting compares all 181 exercises, starter and first correct
body, in both Canonical and AST modes: 724 requests per implementation. The
preserved AP01 classes were independently bound to their earlier 4,274-response
comparison with `521019e`. Only the unsupported outer `workMillis` frame field
is removed for that older worker; complete public response values are compared.

All **1,448 current responses** matched their paired baseline response hashes.
No comparison timed out or returned an incomplete result. Each bulk run recycled
its worker once at the configured 512-task lifetime; that was planned recycling,
not a worker launch per edit.

The [sealed benchmark evidence](../closure/constrained-runtime/evidence/benchmark-20261005T101352Z-32ba7714/README.md)
contains the paired response-hash rows, frozen inputs, resource observations and
constructed timeout counterexamples. It includes no model bodies or deployment
credentials. Its inventory root is
`1aff2ffb2708f22aa8d5f65c6be9379c24db58b62db7a64829c314d9c6fbef69`.

- Two CPUs: current Canonical median/p95 were **46.7/279.3 ms**, versus
  **44.6/274.9 ms** baseline; AST was **31.8/207.1 ms**, versus **31.2/186.0 ms**.
- Four CPUs: current Canonical median/p95 were **44.2/273.6 ms**, versus
  **44.5/273.2 ms**; AST was **30.6/193.3 ms**, versus **30.8/191.9 ms**.
- Each scenario made 14 public visits with zero JVM launches; 12 simultaneous
  identical checks used one computation and 11 joins. Twelve cached checks
  computed nothing. Eight subsequent distinct edits across both metrics
  launched no additional JVMs.
- A forced 20 ms cooperative limit returned `WORK_LIMIT` in **28.8/27.3 ms**
  (two/four CPUs); the same PID then produced the normal preserved response,
  without a replacement launch.
- Behavior fixtures included `productionLineNew-inv3`. All four category
  records were present with at most three instances each. Empty categories
  remain empty when the solver finds no member; no examples are fabricated.
- Sampled child-process RSS was approximately **306–307 MiB**, and total
  benchmark process-tree RSS approximately **441–443 MiB**. These are 50 ms
  observations, not exact peak measurements or an enforced RAM cap.

These results establish finite response preservation and reduced repeated work,
not a general speedup: the two-core timings show some added overhead. CPU affinity
does not simulate an older processor's clock, a VPS CPU quota or memory pressure.
The full 61,598-model SOTA comparison was not rerun for this deployment change;
its existing results and evidence remain intact.

## Formal scope

The new [LP05 work-budget slice](../formal/work_budget/README.md) translates a
restricted set of actual Java charge/checkpoint statements into Lean semantics,
including signed integer wrap behavior, and compares them with an independent
contract. Its theorem audit explicitly lists Lean's foundational dependencies;
it introduces no application axiom or placeholder proof. This is narrower than
whole-program refinement. Charge-site completeness, Java/parser/solver lifecycle,
Python scheduling, provider transport and absolute end-to-end deadlines remain
open production obligations. Historical TRF/AP01 reports retain their original
source roots and are not silently promoted to current closure.

The [final LP05 report](../closure/work-budget/evidence/verify-20261005T103541Z-0cba9231/report.json)
is **VERIFIED for its restricted Java charged-work scope**: seven claims,
14 mapped theorems, two identical offline builds and six fresh hierarchical
reviews (two Luna, two GPT-6.1 Sol, two Astra). It audits 374 declarations and
records the actual `propext`, `Classical.choice` and `Quot.sound` dependencies.
All six changed Java semantics compile and then fail the independent refinement;
placeholder, false-proof and added-axiom controls are rejected. The input root is
`dc3a8d6f85e2f0a31bff417dc41597008d88132f2a358d41ff7704739a0137b9`.
An earlier candidate's offline toolchain-discovery failure and reviews are
preserved; the corrected candidate received the complete fresh review ladder.
CI uses `--replay`, which records its own runtime and can return `REPLAY_PASS`
but cannot renew or manufacture a frozen `VERIFIED` result.

## Application regression record

The [sealed local validation](../closure/constrained-runtime/evidence/validation-20261005T102953Z-842f4bea/report.json)
records **1,228 Python tests, 378 engine checks, 86 public browser checks,
14 administrator browser checks, seven dashboard checks and 69 HTTP comparisons**
passing. It binds the application inputs and rebuilt class inventory to the
benchmark. A later proof-runner-only toolchain-discovery fix and its new test are
validated separately by the offline proof gate and CI replay; they are not
silently included in that Python suite count. Provider integration uses controlled
fixtures or the disabled provider, with no paid calls.

Later hosted CI identified two failing nearest-correct corpus methods among
1,233 executed tests. The [retained failure report](../closure/constrained-runtime/evidence/ci-run-37302832049/README.md)
identifies their registered names without exposing private assertion output.
The [cold-process witness](../closure/constrained-runtime/evidence/cold-pool-20261005T115451Z-1a6b1c40/README.md)
constructs the relevant completion-assumption failure: four fresh JVMs comparing
the same 338-candidate `socialMedia-inv4` pool on one CPU all return `WORK_LIMIT`
at about 8.2 seconds, while serial requests before and after complete in about
3.1 seconds with identical response hashes. The historical CI response codes
were not retained; this witness does not claim to reconstruct them.

The correctness cohort now performs its cold JVM requests serially. All 181
exercises, complete candidate pools, assertions and original time limits remain;
concurrent load and recovery still have separate tests. The revised ten-test
nearest-correct module passed in 148.869 seconds on Python 3.11.16 with two-CPU
affinity. This test scheduling repair changes neither production nor frozen
proof inputs. CI now reports source-registered failing test identifiers and
counts on failure, while continuing to suppress private model/error content.

The private archive created by this run is
`alloy-studio-iis-20261005-102336-623580Z.zip`, with 252 files and SHA-256
`fa5ad06f5ac15e8ba8297391773cf2f8f44921f707a1d41fd9695438f93cdfc8`.
It contains the runtime dependencies and exercise data, and is identified by
hash in the report. It is not copied into public evidence or release assets.

After the native deployment fixes, the separately rebuilt local package is
`alloy-studio-iis-20261005-110143-610052Z.zip`: **252 files, 60,858,146 bytes**,
SHA-256 `fb83fc0d6a1cb36a67f4d393f653ee5ab39a9469c6c429768a5abd7684f985ab`.
This supplements the historical sealed validation artifact above. Native CI
builds its own package from the same source; its ZIP bytes are not identified
by this local archive's hash. Neither archive is a public release asset.

## Deployment

Local runs use `./scripts/run.sh` with the constrained profile by default.
`--resource-profile standard`, `--workers` and `--startup-timeout` are available
through the local launcher. IIS uses the same settings through `-ResourceProfile`,
`-Workers` and `-StartupTimeout`; see the [IIS guide](../deploy/iis/README.md).

Updating package files does not rewrite an installed scheduled task's settings.
An existing explicit worker count stays explicit. To change task settings, use
the documented stop/uninstall/install procedure with the same private backend,
runtime, public paths and origin. Preserve the exercise database, local OpenAI
configuration and administrator configuration. Reapply intended administration
network exceptions to the new public template. The private package is rebuilt
with a timestamped name and is not a public release asset.

The [native IIS run](https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37300331432)
passed **138 recorded checks** on Windows Server 2022 at source revision
`5e550d6451decfaaad904b1aeb0bd30fa27e36f2`; repeated per-worker and per-asset checks
are counted as records. Its [retained summary and hashes](../closure/constrained-runtime/evidence/native-iis-run-37300331432/README.md)
cover real IIS/ARR installation and LOCAL SERVICE execution, 378 packaged engine
checks, all 181 exercise projections, both hint metrics, behavioral rendering
inputs, persistent JVM reuse, administration network denial/admission, clean
restart/stop and preservation of another IIS site.

Three earlier failed native runs remain in the evidence directory. They exposed
multiple executable PATH matches and a PowerShell inline/stdin preflight failure;
the installer now invokes the packaged network-policy validator through a file
interface. The acceptance client also disables .NET's automatic `Expect` header
and paces its catalogue scan below the default request rate. Production protocol
and admission limits remain unchanged. This finite loopback deployment test does
not validate production TLS, Cloudflare forwarding, reboot survival, Windows
desktop installations or live OpenAI calls, and it does not change the user's
production server.
