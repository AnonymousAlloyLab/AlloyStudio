# alpha v0.0.1

Initial Alloy Studio prerelease. Git tag: `v0.0.1-alpha`.

- Switch between ACGN Fast Rewrite canonical distance and raw AST Zhang–Shasha
  distance. Both use the closest member of the complete known-correct pool,
  including the oracle.
- Inspect atomic AST edits with privately checked replay and learner-source
  locations. Repeated terms follow their selected structural occurrence.
- Receive short, per-operation Luna guidance, per-instance explanations, and a
  learning summary without receiving hidden target expressions.
- Explore behavioral similarity and up to three examples in each of four
  oracle/learner acceptance categories, with model facts enforced.
- Open `/dashboard/` for project verification summaries, public GitHub CI runs,
  releases and the 24 open Lean proof obligations. Linux full CI and
  Windows/macOS portability checks are included.
- Read `docs/lean-closure.md` and `closure/lean-obligations.json` for the formal
  implementation plan. Formal closure is not established by this alpha release.

Clone the tagged source and use `./scripts/run.sh` on Linux/macOS, or build the
private IIS deployment package locally with `./scripts/build.sh` on Windows Git
Bash. See the README for prerequisites and deployment instructions. Configure a
private OpenAI key once per deployment; no key is included or entered in the UI.

The private IIS ZIP is intentionally not attached to the public release. The
source distribution includes the bundled exercise data required for cloning;
the installed portal serves only the public allowlist and hides reference data
from learners. Source repository access is not a secrecy boundary for that
bundled data.

This is an alpha: tree edits are guidance, not automatically executable source
patches. Scores and counterexamples are bounded; neither metric is an unrestricted
semantic-correctness proof. CI success does not assert that production IIS was
deployed, and generated prose remains untrusted educational guidance.
