The Java adapter executes the original ACGN Fast Rewrite `Canonical` API. It does
not substitute text, token, Levenshtein, or representative-tree distance for the
framework's temporal + quantifier + matrix metric. This is the Fast Rewrite
compatibility metric, not the certificate-integrated quotient metric.

Build with `./engine/build.sh`. JDK 17 and `vendor/acgn/{src,lib}` are required.
`ACGN_ROOT` can override that dependency root; an optional first argument changes
the output class directory. Run one request per process:

```sh
java -Xmx256m -cp 'engine/build/classes:vendor/acgn/lib/*' live.LiveFeedback
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
paths and rendered fragments identify canonical structure, not precise source
positions. A subtree deletion can contain several unit operations and must be
translated into a syntactically valid learner edit.

`LiveTrace` reconstructs the matrix metric's actual optimal alignment using
ordered child dynamic programming and Hungarian assignment for unordered
children. It reuses the pinned framework's coherent and local alpha mappings
and atomic update costs through narrowly scoped reflection, without changing
the vendored framework. Its operations include CALL identity changes omitted
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

Run `java -Xmx256m -cp 'engine/build/classes:vendor/acgn/lib/*' live.EngineSelfTest`
after building for the focused adapter regression suite.
