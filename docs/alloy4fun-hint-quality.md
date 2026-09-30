# Ten-exercise hint-quality pilot

This pilot compares the **current Canonical/EGraph-backed and raw AST portal
hints with TAR and both FM24 variants**, using the same ten learner drafts.
The clearest finding is a tradeoff: our traces provide more inspectable structure
and source highlights; FM24 often explains an available next step more naturally;
TAR's terse hints accompany a searched, independently checked repair. None of
those properties alone establishes that a novice will repair or learn better.

Collected on **2026-09-30**. The [public evidence](benchmarks/alloy4fun-hint-quality.json)
contains every collected public Canonical/AST operation, canonical display,
source range, baseline hint/status, input identity, and both Luna reviews.
This is an appendix to the [full-corpus comparison](alloy4fun-comparison.md),
not a replacement for its frozen measurements.

## Selection and comparison conditions

We fixed ten exercises from ten distinct model families, chosen for language
and task diversity, **before inspecting their selected baseline outputs**. We
used each exercise's existing portal starter, rather than choosing a particularly
successful or unsuccessful hint. The [frozen selection](../benchmarks/alloy4fun/protocol/quality-selection.json)
records each source-file SHA-256, case identifier, exact learner body, public
environment, question, and selection rationale.

These are typical **construct examples**, not a statistically representative
sample. All ten starters have the corpus label **UNDERCONSTRAINED**, meaning
they admit extra instances: portal *overcoverage*. The catalogue's starter policy
prefers that class. This pilot therefore does not cover overconstrained, mixed,
correct, or syntax-invalid submissions. No unsuccessful baseline case was
replaced after selection.

- Current Canonical and AST: twenty fresh production `/api/feedback` requests,
  a fresh server/cache, one worker, and the portal's **full compatible correct
  pool including the oracle**. The installed engine classes matched the
  recently freshly compiled source tree. All twenty requests succeeded.
- TAR and FM24: exact saved responses from the completed 61,598-case run, matched
  by case/source hashes. They were **not rerun**. The extractor checks the saved
  result-file hashes against the published final report. TAR uses depth two and
  the recorded 60-second budget. FM24 uses the recorded five-fold held-out history.
- We also retain the saved five-fold Canonical/AST results for these same inputs
  as a **pool-policy control**. Current/full-pool and saved/held-out outputs are
  not an equal-training-data experiment. Canonical changes from 26 to 25 edits
  on `socialMedia-inv7`; AST changes from 7 to 5 on `classroom_fol-inv13` and from
  26 to 22 on `cv_v2-inv3`. The other sampled distances are unchanged. These
  observations do not isolate pool policy as the sole cause of every wording
  or trace difference.
- Only deterministic hints are compared. We did **not** generate paid portal
  Luna explanations, evaluate the instance diagrams as a teaching intervention,
  or compare these fresh request timings with historical baseline timings.

FM24 may expose replacement expression identities. Its grade example names a
replacement signature; that identity is marked **[target signature hidden]** in
this report and its public evidence. We retain the action and explicitly record
the redaction. Oracle bodies, historical target expressions, and TAR candidate
repairs remain out of the public artifact. Replacement operator names remain
visible under the portal's existing disclosure policy.

## Observed outputs

Each Canonical/AST number below is its atomic operation count; in this sample it
also equals that mode's distance. Different edit units and internal targets make
these counts unsuitable as a cross-method quality score. A dash means no native
hint, not poor wording. Every emitted TAR candidate in this table passed the
saved independent bounded check against its original model facts and correctness
command.

| Exercise | Feature | Canonical | AST | TAR native hints | FM24 history / +mutation |
| --- | --- | ---: | ---: | --- | --- |
| `classroom_fol · inv13` | Quantification and role restrictions | 7 | 5 | — | Conjunction cue / same |
| `classroom_rl · inv8` | Transpose, restriction, multiplicity | 10 | 4 | 1, checked repair | — / — |
| `coursesOld · inv12` | Ternary relation and per-pair multiplicity | 10 | 3 | 1, checked repair | Signature-change cue / same |
| `cv_v2 · inv3` | Nested quantifiers and distinct identifiers | 33 | 22 | — | — / — |
| `graphs · inv3` | Closure and cycle exclusion | 4 | 3 | 1, checked repair | Negation versus `no` / same |
| `lts · inv3` | Ternary transitions and determinism | 5 | 10 | — | — / — |
| `productionLineNew · inv3` | Domain, join direction, exactly one | 8 | 9 | 2, checked repair | — / — |
| `socialMedia · inv7` | Two-hop navigation and set equality | 25 | 26 | 2, checked repair | — / — |
| `trainStationNew · inv5` | Cardinality and equivalence | 16 | 1 | 1, checked repair | Conjunction cue / same |
| `trash_ltl · inv3` | Temporal scope | 6 | 1 | 1, checked repair | `always` with explanation / same |

Thus hints were available on **10/10** drafts for each portal mode, **7/10** for
TAR, and **5/10** for each FM24 mode. FM24's mutation-enabled variant returned
exactly the same five historical hints and five misses here; this sample cannot
assess the quality of mutation-only FM24 hints. The full-corpus study contains
such hints and remains the availability evidence for that variant.

Canonical supplied **124 operations**: 102 with exact raw-node location metadata,
5 with related raw context, and 17 without a raw location; 119 had exact canonical
node locations. AST supplied **84 operations**: 74 with exact raw-node metadata
and 10 without a raw location. Neither mode supplied an aggregate-only notice
in this sample. These are reported addresses, **not independently annotated
defect-localization accuracy**. A whole expression can still be one exact node,
and multiple insertion steps can share the same anchor.

## Side-by-side case observations

The excerpts preserve the engines' wording, apart from the marked FM24 target
redaction. Each observation considers the complete collected output; excerpts
are shortened for reading. Full faulty drafts and all operations are in the JSON.

### 1. Tutoring roles — `classroom_fol · inv13`

The requirement is that every tutoring link goes from a teacher to a student.
The learner checks only the tutor's role:

```alloy
all p,pp: Person | p->pp in Tutors implies p in Teacher
```

**Canonical:** “Consider the ‘and’ operator in place of ‘in’,” highlighted at
`p in Teacher`, followed by further replacements and repeated insertion anchors.
**AST:** five insertion steps, beginning “Inspect this area for a missing part;
it may need to wrap existing expressions.” **TAR:** no repair/hint. **FM24:**
“Consider adding a conjunction operator ('and') to combine two boolean
expressions,” with context inside the implication.

FM24's conjunction explanation communicates an extension of the condition more
naturally than the canonical replacement wording. **A reproduced literal edit
of that first canonical instruction produces a type error**; see the probes
below. The structural trace itself still requires its other edits and hidden
operands. This exposes a presentation risk, not a disproof of the complete
internal trace.

### 2. At most one taught class — `classroom_rl · inv8`

The learner uses `((~Teaches):>Teacher).(Teaches:>Teacher) in iden`.
**Canonical:** ten steps, including “Consider the ‘.’ operator in place of
‘:>’.” **AST:** four steps, starting with removal of the `:>` structure while
retaining its children, then name-review hints. **TAR:** “Add a binary or unary
operator,” attached to a native source range; its candidate was checked.
**FM24:** no hint in either mode.

The portal provides more inspectable detail, but raw/canonical correspondence
and relation direction still require interpretation. TAR's short cue has a
repair behind it, yet does not tell a novice which operator to add. Four AST
steps versus ten canonical steps does not by itself establish a better route.

### 3. At most one grade — `coursesOld · inv12`

The learner writes `all s : Student, c : Course | lone s.(c.grades)`.
**Canonical:** ten operations; the first proposes `lone` in place of a normalized
`or` and has no raw location. **AST:** three small name-review steps, for example
“Check which name this part of your code should use.” **TAR:** “A different
relation is required,” with a native range and a checked candidate. **FM24:**
“Instead of using signature of type Course, try using signature of type
[target signature hidden] to help satisfy the required property.”

FM24 is more specific about an operand, but part of that specificity comes from
a **different disclosure contract**. The portal deliberately leaves replacement
names to the learner. A normalized operator absent from the raw draft makes the
canonical opening harder to follow without reading its canonical display.

### 4. Identifier uniqueness — `cv_v2 · inv3`

The requirement is per-user uniqueness of identifiers among distinct works
from the same source. The draft quantifies over sources and distinct identifiers
from `(source.s & User.profile).ids`, then compares those identifiers.
**Canonical:** 33 operations, beginning with a useful checklist of variable
domain, multiplicity, and distinctness. **AST:** 22 operations, beginning with
small deletions such as `User` and `ids`. **TAR and both FM24 modes:** no hint.

Both portal modes provide guidance when these baselines return none, but the
long trace imposes considerable interpretation. Repeated insert/delete wording
does not explain the central per-user distinction. Availability is valuable here;
successful novice use remains untested.

### 5. No directed cycles — `graphs · inv3`

The draft is `not iden in ^adj`. **Canonical:** four operations, including a
missing variable declaration and an operator change around `iden`. **AST:**
three replacements, naming `&` and `no`, with one unavailable raw location.
**TAR:** “Transform into a unary expression,” with a checked candidate.
**FM24:** explains replacing negation, which tests falsity, with `no`, which
tests that a set has no elements.

FM24 supplies the clearest explicit distinction between two concepts. AST names
useful operators but offers less rationale. Canonical's nearest-reference route
introduces binding structure rather than simply explaining the learner's
confusion. None of these redacted strings alone proves a successful first edit.

### 6. Deterministic transitions — `lts · inv3`

The learner writes `all s:State, s1:State, e:Event | lone e->s1`, without using
the transition relation. **Canonical:** five steps, including a declaration
review and “Consider the ‘.’ operator in place of ‘->’.” **AST:** ten edits,
including deletions around `s1` and its declaration. **TAR and FM24:** no hint.

Canonical offers a concrete relational-operation cue, with raw addresses for
all five steps. However, the missing connection between the quantified variables
and actual transitions is not explained semantically. This is a good candidate
for a future guided explanation of one grouped operation, rather than treating
five atomic edits as five independent code patches.

### 7. Component assignment — `productionLineNew · inv3`

The learner writes `all m:Material | m in Component => one workstation.m`.
Every component should instead have exactly one assigned workstation.
**Canonical:** eight steps, starting with the variable-domain checklist, then
changes involving `one` and a normalized `or`. **AST:** nine steps beginning with
deletions inside the implication. **TAR:** “A different relation is required”
and “Add a binary or unary operator,” with native ranges and a checked depth-two
candidate. **FM24:** no hint.

Canonical's declaration hint points to an important area to inspect. The
remaining trace does not directly explain why quantifying over materials can
leave the component requirement unchecked, or how join direction matters.
TAR's candidate success and its terse learner-facing explanation are separate
quality dimensions. The new instance visualization was not evaluated in this
text/structure pilot.

### 8. Suggested users — `socialMedia · inv7`

The draft uses a one-way implication with transitive `^follows`, excludes
influencers, and separately excludes self-suggestion; the question asks for exact
two-hop suggestions with specified exclusions. **Canonical:** 25 operations,
beginning with normalized Boolean/set operator replacements, some highlighted
at a large expression. **AST:** 26 operations. **TAR:** a relation-change cue
and a binary-operator change/removal cue, with a checked depth-two candidate.
**FM24:** no hint.

This example prevents interpreting a long distance trace as an intrinsically
good teaching route: it is substantially longer than TAR's emitted mutation
sequence, while TAR still leaves the intended operator choices unexplained.
The approaches are optimizing different representations and output contracts.

### 9. Junction cardinality — `trainStationNew · inv5`

The draft is `all t: Track | t in Junction implies #succs.t > 1`, while the
requirement holds in both directions. **Canonical:** 16 operations beginning
with normalized Boolean changes. **AST:** one step: “Consider the ‘iff’ operator
at this part of your code,” precisely attached to the implication expression.
**TAR:** a binary-operator change/removal hint, with a checked candidate.
**FM24:** suggests adding conjunction and explains combining Boolean expressions.

AST provides the most direct tested edit in this case. Replacing the indicated
`implies` with `iff` compiles, reaches AST distance zero, and passes the recorded
bounded behavior check. This does not mean conjunction-based alternatives are
wrong, or that AST dominates canonical representation generally.

### 10. Persistent file existence — `trash_ltl · inv3`

The learner writes `some File`; the question requires at least one file at every
state. **Canonical:** six operations, starting “Check for a missing condition
about when something holds,” with the whole canonical form highlighted and no
raw location for that temporal insertion. **AST:** one generic wrapping/insertion
hint around `some File`. **TAR:** “Insert an operator,” with a checked candidate.
**FM24:** names `always` and explains that the property should always hold.

FM24 is the clearest available temporal explanation in this sample. Its phrase
“One step away from the solution” is **upstream encouragement**, not a semantic
progress result established by this pilot. AST's one insertion is concise but
withholds the operator; canonical's temporal classification is useful but comes
with five additional structural operations.

## Two reproduced literal-edit probes

These were **targeted diagnostic probes chosen after reading the hints**, not a
random first-edit success experiment. We replaced the single named source
operator inside the first operation's exact highlighted raw range and made no
other change. The private edited candidates were not published.

1. `classroom_fol-inv13`, Canonical `in` → `and`: the production API returned
   `invalid`, diagnostic **TYPE_ERROR**, at learner line 2, column 44. The wording
   can be read as an invalid literal token substitution. The full multi-step
   normalized trace was not applied by this probe.
2. `trainStationNew-inv5`, AST `implies` → `iff`: the API returned `ok`, distance
   **0**. The behavior check reported **1.000**, accepting 100/100 sampled positive
   instances and rejecting 100/100 sampled negative instances, with zero extra
   semantic counterexamples. Undercoverage and overcoverage were `unsat` under
   the recorded scope: 3 atoms, 3-bit integers, sequence bound 3, model facts
   enforced (the configured trace range was 1–10). This is bounded evidence,
   not unbounded equivalence or a learning result.

## Model-assisted review and limits

Two separate **GPT-6 Luna subagent reviews** read the complete collected outputs.
They were not blinded to method names. They rated specificity, localization,
novice clarity, and the next step on an exploratory 0–2 scale; unavailable hints
received `null`. They saw the same questions, drafts, and probe evidence, and
used the same model, so their errors need not be independent.

For the 37 available case/method outputs, their exact agreement was **19/37** on
specificity, **22/37** on localization, **37/37** on novice clarity, and only
**1/37** on next-step ratings. In particular, they interpreted the strength of
“inspect/check” guidance differently. **The next-step rubric is not reliable
enough here for a rating-based ranking.** Both raw review records are retained;
their numerical ratings are not averaged into a leaderboard. The case prose
above uses source-checked observations and distinguishes the two tested edits
from reviewer judgments.

The useful design leads are consequently specific and provisional:

- Group dependent normalized edits into one learner-facing task, and explain
  the relationship to the raw expression. Avoid phrasing an intermediate tree
  relabel as a directly substitutable raw-code operator without checking it.
- Retain both Canonical and AST views. The junction and temporal cases show why
  normalization can expand a simple raw edit; the transition case shows the
  reverse count pattern. Counts alone do not decide pedagogical value.
- Add concise operator-purpose explanations where the target-hiding policy
  permits them. FM24's conjunction, empty-set, and temporal cues illustrate this.
- Preserve explicit source/canonical highlights and disclose related-context
  anchors. A native coordinate or exact tree node does not identify the true
  defect automatically.
- Evaluate grouped guidance with actual learners and a calibrated human rubric
  before making educational-effectiveness claims. Include other error classes,
  mutation-only FM24 cases, and identical training/reference policies in the next
  study.

These are recommendations from the pilot; this documentation task did not change
the production hint algorithms or their wording.

## Reproduction and evidence preservation

The ignored working directory is `build/benchmarks/hint-quality-pilot/`. Original
full-corpus measurements and archives remain unchanged. The public JSON binds
the frozen selection, current source/class files, archive files, selected raw
records, and review inputs by SHA-256. Those bindings and checked source spans
establish artifact correspondence, not a formal proof of educational quality.

For a new collection, use a **fresh** working directory. The scripts refuse to
overwrite existing output files. Build the current engine with
`python3 scripts/build_engine.py` if needed, then, from the repository root:

```bash
STUDY=build/benchmarks/hint-quality-new
mkdir -p "$STUDY/tmp"
cp benchmarks/alloy4fun/protocol/quality-selection.json "$STUDY/selection.json"
export TMPDIR="$PWD/$STUDY/tmp"
python3 benchmarks/alloy4fun/quality_collect.py \
  --selection "$STUDY/selection.json" --output "$STUDY/current.json"
python3 benchmarks/alloy4fun/quality_extract.py \
  --selection "$STUDY/selection.json" --data build/benchmarks/alloy4fun-v2 \
  --output "$STUDY/baselines-verified.json"
```

The collector starts and stops its own local server, disables OpenAI, and calls
only the feedback endpoint. The extractor requires the retained local benchmark
archives, not merely the public summary. The two probes can be repeated against
a running local portal with:

```bash
python3 benchmarks/alloy4fun/quality_probe.py \
  --study "$STUDY" --url http://127.0.0.1:8080 \
  --output "$STUDY/literal-edit-probes.json"
```

Reviews are model judgments and are not deterministically reproduced by those
commands. After supplying explicit `reviewer-a.json` and `reviewer-b.json`
records, `quality_publish.py --study "$STUDY" --collection-date YYYY-MM-DD
--output <new-public-json>` validates
the selected identities, spans, counts, and review coverage, and applies the
documented native-hint redaction. Future baselines with different hint formats
need a fresh disclosure review; this is not a universal text sanitizer.
