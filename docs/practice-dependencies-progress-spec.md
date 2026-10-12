# Dependent practices and portable learner progress

Acceptance contract for v0.0.7-alpha.3, 2026-10-11. This specification precedes
implementation and requires an independent counterexample review. It adds no Lean
closure claim and does not renew historical production correspondence.

## Upload and source preservation

New uploads use a version-three source witness. Versions one and two retain their
original deterministic projections. Derive a graph between selected practice
groups from the actual Alloy call inventory, including calls through retained
helpers and every selected oracle and starter. A practice requires every reachable
selected predecessor. Reject group cycles, including references within the same
numbered solution group. Functions remain helpers. Dependencies through parameter
domains, facts, signature facts or field domains remain prohibited. All original
numbered groups, including unselected groups, still pass facts-aware bounded
equivalence. Source declaration order does not grant authority: the graph does.

Keep unselected source fragments and the complete private upload byte-exact.
Selected predicate names and typed headers stay fixed. Public sibling declarations
contain generated TODO slots, never selected oracle bodies. Selected numbered C
names can have explicitly generated forwarding declarations to the corresponding
base practice, with the same independently inspected argument names and interface.
No variable or argument is guessed or reordered. Forwarding declarations are
derived wrappers, not changes to the uploaded predicate bodies. Group cycles are
rejected before generating them.

The witness records the validated Alloy inspector's actual call graph and
parameter inventory, bound to the unchanged source and validation certificates.
Reload deterministically reconstructs slots, forwarding declarations and private
validation environments from that inventory; it does not claim to repeat the
compiler's semantic inspection without a JVM. The backend-owned preparation and
private SQLite publication remain the authority for the original compiler check.
Missing, extra, forged or altered metadata is rejected
before publication and during SQLite reload. Dependency metadata is stored in the
existing normalized record/auxiliary machinery; SQL stays fixed and parameterized.
The 100-group/256-solution, 256-KiB source, 8-KiB body/header, 16-argument, 60-second,
two-sequential-JVM, batch frame/byte, draft and database limits remain in force.

Generated exercises are validated in a private environment containing the primary
correct implementations of their prerequisites. Bind the certificate to both the
public projection and this private validation environment. All source variants are
checked before deduplication as before. Never certify a dependent practice against
blank prerequisite slots. The public version includes dependency/slot structure
but not hidden solution bodies. Existing independent records keep their version
calculation.

Removing a practice that has active dependents is refused; administrators must
remove dependent practices first. Missing or archived predecessors fail closed.

## Backend completion authority and execution

An earned browser checkmark is display history, not authority. The backend issues
an opaque 256-bit completion token only for the same current exercise, body,
metric, dependency context, channel and structural evidence that also has a
complete successful behavioral result. Structural distance must be exactly zero;
the behavioral score must be exactly one with no semantic counterexamples,
completed UNSAT undercoverage and overcoverage, agreeing positive/negative samples
and positive/negative witnesses. Reuse the already pinned structural result from
the normal feedback request: issuing a token never starts a JVM or solver.

Tokens reference immutable last-verified learner implementations, not the latest
unverified draft. Retain at most 1,024 records and 8 MiB of serialized completion
data, with a 24-hour absolute expiry, monotonic expiry checks, bounded LRU eviction
and synchronization. The token does not contain a password, provider key or
oracle. State is private, process-local and discarded on restart. No account,
shared server draft store or server signing-key file is introduced.

Dependent requests supply only the opaque tokens for their exact direct
prerequisites. The backend follows the complete verified chain, rejects unknown,
expired, foreign, duplicate, missing or excess tokens, and checks every recorded
exercise/version and dependency context against its captured catalogue. Client
booleans, imported badges, timestamps, hashes or learner-supplied dependency graphs
cannot authorize execution. Existing independent request shapes remain valid;
the existing 16-KiB learner ingress bound is unchanged.

Resolve the learner's verified prerequisite bodies into the fixed slots. Both the
student model and all reference/behavioral oracle models use exactly that resolved
environment. Preserve the immutable base public content version and report a
separate dependency-context identity. Bound the generated model and engine frames
before scheduling. Oversized contexts receive a bounded error, not truncation.

The live compiler must reject student calls, including transitive helper calls,
into selected practice slots outside the verified dependency closure. This guard
applies to Canonical, AST and behavioral engines; blank downstream slots cannot be
used as true predicates. Target recursion and facts/domain dependency checks remain.
Dormant incoming aggregate callers are still allowed.

Include the resolved environment/context in scheduler, cache, evidence, behavioral
pool and Luna identities. Stale feedback cannot earn a completion, explain a
different context, retain a candidate under another question, or unlock a practice.
Publication invalidates old-version authority. Returning an expired/locked result
does not solve, start a JVM, call a provider or increment a worker launch budget.

Candidate admission keeps its existing no-counterexample gate and immutable base
exercise identity, while recording the dependency-context identity of its observed
behavior. Review remains advisory and administrator-triggered. Final pool approval
rechecks the candidate using the private author-validated prerequisite environment,
not blank slots or a mutable student's draft. Existing candidate features remain.

## Learner interface and saved progress

Show prerequisite predicate names on locked practices. Disable their editor and
analysis actions until verified implementations are available. Display which saved,
last-verified implementations are used in the model environment; editing a draft
does not silently replace them. Checking and completing a new implementation
invalidates that browser's dependent completion contexts and pending results before
reuse with the new implementation. An older immutable receipt can still represent
its original consistent, correct dependency chain; one anonymous learner's new
implementation does not revoke another learner's receipts.
Navigation skips unavailable practices or shows an explanatory locked state without
an engine request. Canonical remains the default. A bounded `/api/progress` token
check accepts at most 200 tokens, returns only valid completion identities and
expired supplied tokens, and starts no JVM or provider call. Tokens are bearer
receipts within one backend lifetime: channel binding applies to minting evidence,
not later redemption after a browser reload.

Keep autosaving individual drafts and existing historical checkmarks. Add explicit
Save progress and Load progress controls for a versioned portable JSON file plus
an explicit browser save indication. Save drafts, last-verified learner code,
exercise IDs, public content versions, selection, comparison preference and bounded
history. At most 200 exercise-code records, 8 KiB per body, 12 history points per
metric, and 4 MiB per serialized file are admitted. Refuse overflow explicitly;
never silently discard learner code. The complete file is validated before any
storage or editor mutation. Unknown/malformed fields and prototype-like keys are
rejected. Removed or changed exercises are reported and omitted from restoration.
Storage quota failure is visible; no success is shown for a failed browser write.

No oracle, provider key, administrator configuration, server authority secret or
instance pool is exported. Optional completion tokens remain untrusted on import;
only a live backend can accept them. Save files work between Linux, macOS and
Windows and require no private IIS archive or original ACGN checkout.

After expiry, eviction, restart or transfer, saved code remains available. Provide
an explicit restore-check action that revalidates saved implementations in graph
order through the ordinary persistent feedback/behavior lanes. Use one sequential,
cancellable restore operation; do not generate Luna explanations or paid provider
calls during restoration. Failure stops the affected chain and leaves downstream
practices locked. File changes, catalogue changes, selection changes and new user
edits cancel stale restoration. Never manufacture authority from a progress file.

## Required witnesses

1. A -> B -> C unlocks in order; helpers and parameterized C forwarding work.
2. Forging local solved bits or importing a fake completion cannot execute B.
3. A `{ some Node }`, B `{ A[] }` is never checked against blank A `{}`.
4. A changed body, version, token or ancestor invalidates the affected chain;
   stale responses and mixed dependency contexts cannot issue a completion.
5. A learner call to an unsolved sibling, including through a helper, is rejected
   by all three live engine paths before it can count as successful evidence.
6. Group cycles, facts/domain dependence, malformed arguments/aliases and invalid
   unselected C variants are refused without partial publication.
7. Save/load round trips drafts and verified code; corrupt, oversized, stale and
   malicious files cannot change authority or partially overwrite progress.
8. Restart/eviction restoration reuses persistent JVM identities and stops on
   failure/cancellation; listing, saving, loading and a rejected unlock launch none.
9. Dependency-aware candidate capture/approval, SQLite reload, legacy witness
   verification, independent exercises and all supporting portal features pass.
10. Constrained 100-practice preparation and relocated IIS/Linux/macOS builds stay
    within their registered limits; final release inputs exclude credentials.
