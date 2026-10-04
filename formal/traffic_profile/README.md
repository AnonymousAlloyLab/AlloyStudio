# TCFG02: HTTP profile and initial admission state

The independent [specification](../../docs/http-profile-obligation.md) and
[machine-readable obligations](../../closure/traffic-refinement/http-profile-spec.json)
precede this block. Its finite claim is `TRF00-HTTP-PROFILE-INIT`, a child of TRF-00.
It does not close TRF-00 or any other complete original traffic obligation.

The production module `traffic_profile.py` owns 23 registered fields and their
existing scalar/relational validation, defensive normalization, and the initial
admission record. `traffic_http.py` consumes that record in Admission and
TokenBucket and validates the listener boundary before runtime allocation.

`scripts/http_profile_bridge.py` recognizes complete admitted profile syntax,
extracts actual relation and initial-state expressions, generates
`TrafficProfile/Extracted.lean`, and checks the runtime constructor consumers.
The templates, independent schema, source and bridge are frozen inputs. Unsupported
syntax, fields, type/copy bypasses, rebinding and constructor changes fail closed.

`Model.lean` defines the explicit normalized inputs and abstract state.
`Spec.lean` independently defines acceptance and the initial-state relation, then
proves scalar preservation, all profile relations, selected limits and credits,
empty ownership, initial-state validity and uniqueness, and boundary/default
witnesses. The clock origin is an explicit signed integer, not an assumption of
repeatable clock callbacks. Empty Python sets/lists/maps have an explicit empty
abstract representation; concrete object/lock identity is outside the claim.

The fixed compiler flags include `-DmaxRecDepth=4096` uniformly; individual proof
sources cannot set options or override the registered checking policy.

The block reuses the unchanged TCFG01 scalar sources. Its audit registers 95
empty-axiom theorems: 55 reused and 40 new, including generated/private declarations.
All project definitions are audited too. Kernel proofs admit no placeholders,
introduced axioms or native evaluation. The registered verifier
`python3 scripts/verify_http_profile.py` checks two clean identical builds using
pinned Lean 4.34.1 in network namespaces, actual AST correspondence, six proof
mutation controls, axiom/placeholder controls, runtime/bridge/verifier tests, and
the source-bound 2 Luna → 2 GPT-6.1 Sol → 2 Astra review ladder.

The declared TCB includes the Lean toolchain, frozen translator/linkage/auditor,
Python exact builtins and dataclasses, the initial container/lock/callback
interpretation, and host isolation/hashing/hardware. It excludes hostile trusted
code or concurrent mutation during normalization. A successful report applies
only to its frozen source root under that TCB. Reviews are advisory and cannot
supply or override kernel evidence. Future admission histories, complete service
configuration/observations/initialization, resource containment and real platform
deployment remain outside this block. Consult the verifier's machine-readable
report for completion status; development compilation is not closure.
