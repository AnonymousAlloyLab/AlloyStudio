# LP05 charged-work implementation slice

This candidate covers only `WorkBudget.charge`, `sampleClock`, and `checkpoint`
translated from admitted Java branches/assignments into generated Lean functions.
The independent contracts live in `Semantics.lean`; `Refinement.lean` connects
those generated definitions to the contracts and to the earlier atomic-work fold.
The parser and lowering are explicit trusted machinery, not a verified Java
compiler. Source publication guards are checked structurally, not proved as a
whole-program refinement. A hash match alone is not the claimed semantics bridge.

No result is established by this file: the machine-readable verifier report for
the exact frozen `block.json` is authoritative. `--prepare` creates only a review
candidate; `--verify` requires six fresh hierarchical reviews, two isolated offline
builds, the complete declaration/type inventory with only explicitly allowlisted imported Lean foundations,
source extraction equality and registered negative controls. Reviews are advisory,
not proof evidence. No Internet is available to Lean processes.

The following seven claims have fixed theorem mappings in
[`spec.json`](../../closure/work-budget/spec.json). Their hypotheses are visible
in the theorem types. In particular, a valid state bounds remaining signed fuel
and countdown; initialization of the complete Java object, complete charge-site
coverage, global wall time and other runtime behavior are not silently inferred.

## LP05-WORK-C01

Signed 64-bit and 32-bit wrap models are identities on their represented signed ranges.

Theorems: `javaLong_exact`, `javaInt_exact`.

## LP05-WORK-C02

The parsed checkpoint branches equal the independent checkpoint contract for every modeled state and observation.

Theorems: `checkpoint_refines`.

## LP05-WORK-C03

For a valid countdown, the parsed sampling method equals its contract, the source initializer is 1024, each earlier timed call decrements, and a due call observes the clock.

Theorems: `initial_cadence`, `sample_refines`, `timed_countdown_decreases`, `due_sample_observes_clock`.

## LP05-WORK-C04

For a valid state, parsed charge equals the independent charge contract; a successful active charge admits its entire nonnegative cost and subtracts exactly that cost.

Theorems: `charge_refines`, `admitted_charge_cost`.

## LP05-WORK-C05

Sample and charge preserve the declared remaining-fuel and countdown invariant.

Theorems: `sample_preserves_valid`, `charge_preserves_valid`.

## LP05-WORK-C06

An admitted batch length satisfies the existing Work.boundedFold complete-batch contract.

Theorems: `admitted_batch_matches_atomic_contract`.

## LP05-WORK-C07

An exhausted active checkpoint remains exhausted; a timed active checkpoint rejects an observation at or beyond its limit.

Theorems: `exhausted_checkpoint_sticky`, `expired_publication_refused`.

## Scope and trust

PROVED, if the frozen gate passes: the seven claims above about generated
restricted-method semantics and their independent constructive Lean contracts.
CHECKED: exact source-to-generated equality, unique method mappings, strict Java
subset admission, publication guard structure, complete declaration inventory,
exact axiom dependency inventories, immutable hashed inputs and deterministic proof objects.
TESTED: registered malformed-source and semantic-control mutations are rejected.
TRUSTED: Lean/toolchain and exactly its imported `propext`, `Classical.choice`,
`Quot.sound` foundations, restricted Java parsing/lowering, ordinary JVM primitive
and ThreadLocal semantics, Python, OS/network namespace, hardware, SHA-256 and
review identity provenance, as enumerated in the spec. Actual dependencies are
recorded for every theorem and definition. This is not a zero-axiom proof block:
Std signed-integer arithmetic and tactics use these standard foundations. No
project axiom declarations, `sorry`, extra axioms or unchecked proof escape are
admitted. The earlier AP01 zero-axiom block is preserved unchanged.

OUT OF SCOPE: entire Java/Python/browser refinement, all charge sites, parser and
solver native allocation, every constructor/setup helper, provider socket reads,
absolute wall-clock or CPU/RSS guarantees, Windows/IIS/Cloudflare deployments,
and learner outcomes. Clock observations are explicit integer inputs. A theorem
that rejects an observed expired clock does not prove when an OS schedules that
observation. All broader AP01 production bridges remain OPEN.

Run from the source root with the preinstalled pinned Lean toolchain:

```sh
python scripts/verify_work_budget.py --prepare
# Record the actual two Luna, two GPT-6.1 Sol, two Astra reviews against this block.
python scripts/verify_work_budget.py --verify
# CI portability replay: frozen inputs/reviews, current explicitly reported runtime.
# This returns REPLAY_PASS, never grants or renews VERIFIED.
python scripts/verify_work_budget.py --replay
```

Every attempt receives a unique directory under `closure/work-budget/evidence`.
Failed attempts remain intact. A relevant input change requires a new candidate
and new review bindings; an older VERIFIED report cannot certify changed code.
Replay mode still requires the exact frozen source/claim/declaration inventories
and six matching reviews, the pinned Lean version, two clean offline builds and
all negative controls. It records actual Python/toolchain hashes but does not
require them to equal the original local runtime. This distinction permits CI
Python-version differences without misrepresenting that replay as frozen closure.
