# Finite guidance evidence for v0.0.5-alpha.1

This record tests the prompt and response boundaries and records four local
GPT-6 Luna tutoring observations. It does not prove that every future provider
response follows the educational instructions. No live Responses API call or
private credential read was made for this record.

- [Targeted regressions](targeted-tests.txt): 126 tests passed in 18.694 seconds
  under Python 3.10.11 with `OPENAI_DISABLED=1` and scratch inside `build/`.
  Modules: `test_guidance_context`, `test_education`, `test_metric_modes`,
  `test_luna_sharing`, `test_education_http`, `test_credentials`,
  `test_traffic_portal`, `test_ingress_functional_invariance`, `test_cicd`.
  Checks cover the captured question and complete correct pool, strict bounds,
  projection of private fields, exact cache identity, concurrent sharing,
  credential relocation and pinned feedback without a new analysis call.
- [Exact public inputs](fixtures.json) and [Luna outputs](tutor-outputs.json):
  A uses concise matching learner code; B uses matching repeated code;
  C uses `no` against an existence question with an approved `some` hint;
  D has the same operator context with no question. These are synthetic
  feedback fixtures, not observations of Java normalization or equivalence.
  Their comparison counts are public toy metadata. All examples are unavailable.
- [Qualitative assessment](review.txt): a local GPT-6 Luna agent evaluated the
  exact serialized prompts and current instruction literal. The production
  response validator accepts all four saved outputs, with their expected IDs
  and length/shape limits. No proposed solution or whole repair route was found
  in these four observations. This is a finite observation, not a semantic
  non-disclosure guarantee or an end-to-end provider integration test.
- [Build receipt](report.json): a freshly compiled private IIS archive has 252
  entries, all byte-checked against the current package allowlist. The 26 frozen
  LP05 inputs retain their original hashes. The archive is intentionally absent
  from public release attachments.

[The manifest](manifest.json) binds production prompt code, its source lexer,
the targeted test sources, exact fixture/output bytes and the instruction literal.
The replay checks these hashes, reconstructs the inputs through
`prompt_education`, and validates each saved output through `_education_response`:

```sh
OPENAI_DISABLED=1 python3 docs/evidence/guidance-alpha1/replay.py
```

The replay uses a synthetic marker for the response validator and never calls
`read_key`, a provider transport, the Java engine or a solver. It checks saved
evidence; it does not regenerate natural-language output.
