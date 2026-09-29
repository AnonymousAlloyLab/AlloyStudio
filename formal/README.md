# Offline constructive proof blocks

Lean is pinned to **4.34.1** at the repository root and here. The package uses
only its bundled standard library. Updating/installing the toolchain is a
separate preparation step; the proof process never invokes elan or downloads
dependencies.

Run from the repository root on Linux with user/network namespaces enabled:

```sh
python3 scripts/verify_lean.py
```

The command uses the absolute installed toolchain, creates two fresh build
directories, and runs **every Lean subprocess in a new network namespace**.
Each namespace has only loopback, no IPv4 routes, and fails an outbound socket
probe. The proof subprocess environment excludes deployment credentials and
shell/plugin hooks. Unsupported isolation fails with `INFRASTRUCTURE_FAILURE`;
there is no online fallback. Linux is needed for this proof verifier, not for
running the portal on Windows or macOS.

Every compilation uses:

```text
--trust=0 -DwarningAsError=true -DgenInjectivity=false -j1
```

`Audit.lean` enumerates all project theorems in the selected frozen blocks, including generated/private names,
their exact elaborated expressions, universe parameters, owner modules and
transitive axioms. The accepted axiom list is **empty**. No `sorry`, custom axioms,
native proof evaluation, unsafe definitions or proof-source metaprogram escapes
are admitted. The registered audit metaprogram itself is explicit verifier trust.
Ordinary theorem parameters and proved preconditions are not undeclared axioms.

The manifests freeze source hashes and exact theorem/type inventories. Both clean
builds must produce identical `.olean` hashes and audit inventories. Installed
toolchain binaries/libraries and verifier inputs are hashed before and after the
run. Deliberately invalid scratch fixtures check that a placeholder is rejected
by compilation and a custom axiom plus its dependent theorem are rejected by the
audit. These rejected fixtures are outside the accepted proof library.

| Block | Constructive content | Deliberate boundary |
| --- | --- | --- |
| B01 | Forest counts/postorder; promotion/adoption/relabel operations; contextual script inverse and composition; complete pool evaluation and first minimum; oracle inclusion; closure-decision arithmetic | No parser/label-encoding proof, insertion freshness, Zhang–Shasha optimality, normalization proof, or Java refinement |
| B03 (active, supersedes B02) | B02's request/echo models; fixed-arity Boolean guard equivalence; complete pool scan and positional certificate; four generated implementation policy kernels | No universal host-loop, wire-decoder, comparison, asynchronous scheduling or algorithm refinement |
| B02 (historical) | Current-request and response-echo guards; stale/aborted/metric rejection; guidance generation/token binding; AST context separation; logical metric cache separation | Frozen against the earlier browser implementation; retained for audit history |

The default gate checks **B01 + B03**. B03 connects four small runtime policies
to Lean definitions: successful feedback, successful guidance, incumbent pool
replacement, and complete-pool publication. Lean exports their truth tables;
the verifier generates and kernel-checks **9,224 individual row theorems** with
empty axiom sets. It executes the actual JavaScript kernel over all 9,224 typed
Boolean inputs and the actual Java kernels over their eight inputs. The exported
values are not proof authority: every row is independently proved by the kernel.
Generated source must match exactly, and a deliberately false tie row must fail.

Both production distance modes use the generated pool decisions, and successful
feedback/guidance uses the generated browser guards. Runtime mutation tests
check those connections. This establishes finite policy correspondence under
the recorded runtime/compiler and verifier trust boundary; it does not establish
arbitrary host iteration, wire decoding or algorithm correctness. The complete
scope and remaining work are in [the implementation bridge overview](../docs/implementation-bridges.md).

B02 preserves two executable counterexamples: the old canonical feedback guard
accepted successful feedback with no exercise, revision or metric echo; the old
guidance guard accepted a missing metric echo. The strengthened model rejects
both. `web/app.js` applies the corresponding stricter guards, and
`tests/browser.mjs` exercises missing/mismatched echoes in both metric modes.
Source hash binding and browser tests are supporting implementation evidence,
not a universal language-semantics proof. Canonical remains the default metric.

Each frozen block has the requested sequential review ladder: **two GPT-6 Luna**
reviews, then **two GPT-6 Sol** reviews that read both Luna reports, then **two
GPT-6 Astra** reviews that read all four earlier reports. Each higher tier records
the earlier JSON hashes. Reports and coverage notes live under `reviews/`.
A claimed invariant breach requires a concrete input, expected property,
observed result and executable witness; it must be replayed before classification.
An unresolved finding blocks the gate. A changed proof block needs a fresh
ladder. Review opinions never substitute for kernel evidence, and an explicit
unproved boundary is not a counterexample to a narrower theorem.

Reports are written under `build/lean-verification/`. `blockStatus: VERIFIED`
means only the selected frozen mathematical blocks passed. The overall `status`
remains **BLOCKED**, with exit code 1, because the original 24 end-to-end
implementation obligations are not discharged. `bridgeStatus: VERIFIED` refers
only to the four finite policies. Changing ledger labels cannot bypass the
remaining semantic correspondence requirements. Exit code 2 means an infrastructure failure.
The portal regression closure has a separate verifier and must not be advertised
as Lean closure.

Generate an obligation overview bound to a particular report:

```sh
python3 scripts/obligation_overview.py --report build/lean-verification/RUN/report.json --output build/obligation-overview
```

Missing, stale or incompletely bound evidence cannot produce a verified bridge
overview. All original obligation rows remain OPEN.

The exact types and source files define what was proved. Kernel correctness,
installed toolchain integrity, the audit/Python verifier, review orchestration,
hashing, namespace enforcement, OS and hardware remain explicit trust boundaries.
No claim of assumption-free physical execution or universal portal correctness
is made.

The separate SQL code/data separation block lives under `sql/`. Run
`python3 scripts/verify_sql_separation.py` from the repository root to check its
frozen source mappings, six ordered reviews, two offline Lean builds, theorem
inventory and exact registry instantiation. It does not alter the B01–B03 blocks
or close the original whole-portal obligations. The
[SQL boundary](../docs/sql-injection-proof.md) distinguishes proved statements,
checked source extraction, runtime witnesses, and trusted semantics.
