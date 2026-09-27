The Java adapter executes the pinned ACGN Fast Rewrite `Canonical` API. It does
not substitute text, token, Levenshtein, or representative-tree distance for the
framework's temporal + quantifier + matrix metric. This is the Fast Rewrite
compatibility metric, not the certificate-integrated quotient metric.

Build with `./engine/build.sh`. Python 3.10+, a JDK 17+ with `javac`, and
`vendor/acgn/{src,lib}` are required. The entry point uses the shared
`scripts/build_engine.py` compiler and defaults to `build/engine/classes` under
the project root. An optional first argument changes the output class directory.
Compilation uses the bundled `vendor/acgn` dependencies. This engine-only
command does not create or update an IIS archive. PowerShell's equivalent is
`powershell -NoProfile -File scripts/build.ps1 -EngineOnly`.

Use `./scripts/build.sh` or
`powershell -NoProfile -File scripts/build.ps1 -RequireNode` for the portal build,
which also refreshes the IIS ZIP and checksum. The standalone packaging command
`python3 scripts/package_iis.py` compiles Java afresh before packaging as well;
it requires a JDK and accepts `--javac`, `--classes-output`, `--source`, and
`--output`. Build tools remain in the source checkout; the precompiled IIS
distribution needs a Java runtime, not a compiler. Run one request per process:

```sh
java -Xmx256m -cp 'build/engine/classes:vendor/acgn/lib/*' live.LiveFeedback
```

The process reads one UTF-8 JSON object from stdin, then exits after writing one
JSON object to stdout. Input is `{studentSource, oracleSource, predicate}` where
both sources are complete independent Alloy modules. This is a private server
contract: a browser must never supply or receive `oracleSource`.

Success returns `status: "ok"`, `metric`, `distance`, `breakdown` (temporal,
quantifier, matrix), learner-only `canonicalForm` (array of IR strings),
`canonicalSize`, `operations`, `operationSummary`, `trace`, and `diagnostics`.
Each operation has `kind`, `component`, `path`, `cost`, `aggregate`, and
`description`. Matrix operations additionally identify the affected learner
canonical fragment (`sourceTerm`), its `sourceOperator`, `sourceNodeKind`, and
`sourceRole`, plus concrete `action`, `reason`, and `nextStep` text. Insertions
point to their nearest existing learner expression as an `insertion-anchor`;
a new temporal phase uses an explicit no-existing-matrix placeholder.

When an operator changes, `replacementOperator` exposes only an approved operator
token, such as `no` changing to `some`. Reference names, constants, variable
identities, domains, subtrees, canonical forms, and source remain private. An
identity change instead identifies the learner's current reference, constant,
variable, or call and says which kind of item needs revision. Inserted reference
operators and operands remain hidden. Paths use original learner child indices,
including when an unordered assignment reorders the matched children. These
paths select canonical structure; retained parser origins separately identify
source occurrences. A subtree deletion can contain several unit operations and must be
translated into a syntactically valid learner edit.

Operations also carry `sourceLocation` and `canonicalLocation`. Each has a
`status` (`located`, `ambiguous`, or `unavailable`), a `precision`, UTF-16
end-exclusive `ranges`, and an explicit coordinate system. Source ranges refer
to the learner module and are taken from parsed expressions inside the selected
predicate; helper bodies and declaration positions are excluded. Canonical
ranges include `formIndex` and refer to the corresponding `canonicalForm`
string. Recorded structural paths select the corresponding rendered occurrence,
including when equal text appears elsewhere. `precision: "node"` requires
`status: "located"` and exactly one range. Source locations use the selected
node's parser origin when retained. Normalization can merge or remove that
identity; source lookup then reports related or ambiguous context, or an
unavailable location. Canonical whole-form context is reserved for temporal or
grouped edits and quantifier insertions without an existing binding. Invalid
paths, missing learner phases, and renderer mismatches report unavailable
canonical locations. Node identity is not proof of a defect or an executable
source patch. Neither locator accepts reference source or target expressions.

The HTTP layer validates and rebases source ranges to the editable body, derives
snippets and body/model coordinates itself, and rejects invalid or incomplete
mapping sets. It compacts whitespace outside quoted literals in canonical display
strings and remaps canonical ranges before returning them. These presentation
transformations do not change the canonical metric. The browser binds locations
to the checked exercise, revision, and exact draft, and clears them on edits.
The educational Luna prompt can include learner-only source and canonical locator
data after validating spans against the exact learner text. Private target data
remains excluded.

`live.BehaviorFeedback` is a separate JSON worker for `POST /api/behavior`.
Its private input contains `studentSource`, `studentBody`, `oracleSource`, and
`predicate`. Both modules are checked independently; their environments must
match after removing the selected body. Recursive predicates and facts/shared
helpers depending on that predicate are rejected. The learner expression is
then evaluated in the oracle module with shared signature identities.

The worker uses ACGN's scope/pool constants and Rewarder product formula, with
all reachable model facts conjoined to every sampling and category query.
This is an explicit correction to upstream fact omission. Up to 100 instances
per oracle polarity determine the score; perfect sampled classification triggers
the original one-per-satisfiable-direction denominator correction. Each of the
four oracle/learner truth combinations is separately solved for up to three
examples. The output contains rounded score, sample counts, bounds, category
statuses, and atom/relation tuples for each temporal state, with enumeration and
truncation flags. No original commands, source, XML, or skolems are serialized;
String atoms use consistent opaque identifiers within each instance.

The HTTP boundary revalidates counts, rounded score arithmetic, category
identities and statuses, witness shapes, and limits, then projects an explicit
allowlist. A missing oracle polarity leaves the score unavailable; it does not
invent a perfect score. The browser treats these results independently from
canonical feedback and discards stale draft responses.

`LiveTrace` reconstructs the matrix metric's actual optimal alignment using
ordered child dynamic programming and Hungarian assignment for unordered
children. It reuses the pinned framework's coherent and local alpha mappings
and atomic update costs through narrowly scoped reflection. The vendored
framework includes a local parser-origin presentation metadata patch for the
locators; its base commit and delivered hashes are recorded in
`vendor/acgn/snapshot.json`. The trace includes CALL identity changes omitted
by upstream `Canonical.edits`, and avoid the upstream trace's independently
sorted ordered alignment. Quantifier edits likewise backtrack the metric's
binding-tuple dynamic program.

Before release, each matrix reconstruction privately rebuilds the reference's
metric-labelled tree from the chosen source alignments and unit operations.
Zero-cost alpha mapping and unordered permutation are part of that metric
view. Both reconstructed cost and replay must agree with the authoritative
matrix component; failure returns an unavailable comparison. The public
`trace.matrixReplayVerified` flag reports this in-process check. Java negative
controls verify that omitted and corrupted replacement events are rejected,
and small assignment witnesses are compared with exhaustive permutations.

Temporal feedback still uses upstream explanatory operations. If its count
differs from the temporal metric, an explicit `kind: "component-edit"` aggregate
carries the exact temporal cost; `trace.hasAggregates` marks this case. Matrix
operations never use that fallback. The sum of public operation costs equals
the distance. This is metric-view replay under the pinned framework, not an
independently certified semantic proof or an executable source patch;
`trace.certifiedOptimalScript` remains false. Zero distance reports no operations
and does not establish unrestricted Alloy semantic equivalence or exercise
satisfaction.

The replacement token allowlist is:
`and or not implies iff = != > >= in < <= !> !>= !in !< !<= some no one lone all
-> . <: :> & ++ + - * / % << >> >>> set exactly ~ ^ # int Int ' before historically
once always eventually after until releases since triggered if-then-else disj sum`.
Any internal or unlisted opcode produces no replacement token.

Errors use `invalid`, `unsupported`, `invalid_request`, or `engine_error` status.
Learner parse errors contain a stable diagnostic code and, when available,
module-relative `line` and `column`. Raw parser text, paths, stack traces, and
reference diagnostics never leave the adapter. Historical framework console
output is suppressed. The caller is responsible for enforcing process timeout,
body-only editing, module/import policy, input size, and concurrency limits.

Run `java -Xmx256m -cp 'build/engine/classes:vendor/acgn/lib/*' live.EngineSelfTest`
after building for the focused adapter regression suite.
