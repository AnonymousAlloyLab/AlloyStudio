# TING01: read-phase completion deadlines

This block proves the source-extracted deadline comparison and guard-placement
programs under the [declared boundary](../../docs/ingress-deadline-obligation.md).
The independent evaluator permits unguarded delivery; the separate program
policy rules it out in the registered consumers. No validity flag is assumed.

Run `OPENAI_DISABLED=1 python3 scripts/verify_ingress_deadlines.py` from the
repository root. Proof subprocesses use the installed pinned Lean 4.34.1 inside
an isolated network namespace. Nothing is fetched. Every theorem and project
definition is audited for transitive axioms; placeholders and rogue axioms are
negative controls. The machine-readable report determines the result.

This is child `TRF01-DEADLINE-CUTS`, not complete TRF-01 or backend closure.
The original obligation statements and prior evidence remain unchanged. A new
source root needs a fresh gate; historical VERIFIED evidence is not inherited.
