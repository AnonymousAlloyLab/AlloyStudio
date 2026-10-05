# Question-aware guidance and alternative solutions

`v0.0.5-alpha.1` improves the tutor instructions and the evidence sent to GPT-6
Luna. The question is the captured exercise record's `description`, which is the
same text displayed above the editor. A client cannot override it in an explain
request. Each edit still carries only learner fragments, recorded locations and
approved operator hints. The tutor connects the chosen operator's meaning to a
specific requirement, explains a possible conflict, and asks what to inspect.
An insertion anchor or incomplete context is not automatically called defective.

When the selected distance is zero, the tutor acknowledges the match and invites
another independently written solution with the same intent. The backend scans
the existing complete correct pool, including the oracle, using the existing
Alloy source lexer. It sends only the learner's token count, the minimum known
count and a comparison boolean. Comments and spacing are ignored; string contents
are opaque tokens. This is a bounded lexical length measure, not a raw/canonical
AST-node count, readability verdict, or globally optimal solution claim. If the
learner is longer, the tutor can encourage a shorter formulation; otherwise it
still invites another approach. Missing comparison data produces no invented
shortest-solution claim. Zero distance retains its existing metric semantics.

The prompt evidence uses `alloy-education-v2`. The structured response schema,
operation IDs, instance IDs, length checks and anti-solution shape guards retain
their existing contract. Question strings are untrusted data, never system
instructions. Both the question and serialized comparison are included in the
existing exact-byte cache identity, so a changed requirement or pool minimum
cannot reuse a prior explanation. Each explanation request retains only its
captured exercise pool. Pinned feedback adds no new analysis JVM; the length
comparison is a bounded Python scan and runs only for zero-distance guidance.
Credential setup imports the lexer lazily so standalone configuration relocation
does not acquire an exercise-data dependency.

The question is bounded to 8,192 UTF-8 bytes. Code bodies are individually bounded
to 8,192 bytes; a comparison accepts at most 2,048 correct bodies and 1 MiB of
aggregate reference bytes. The complete prompt retains its 128 KiB bound.
Counts reject booleans, negatives, inconsistent learner counts and comparison
flags. Unknown private fields are projected away. An unavailable length
comparison leaves ordinary guidance available. Reference bodies and identities
never enter the numeric comparison or provider request.

The [finite evidence record](evidence/guidance-alpha1/README.md) binds the source
and prompt hashes to 126 passing targeted tests and four local GPT-6 Luna examples:
matching concise code, matching repeated code, a chosen `no` operator against an
existence requirement, and an absent question. These use public toy learner
fragments and a controlled provider harness. They assess encouragement and
question-specific operator meaning without exposing or generating a solution.
They are not live Responses API observations or a proof of every generated
sentence. Existing Java/Lean work-budget inputs and historical evidence remain
bound to their original hashes.

The existing strict structured-output contract follows the official
[Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses),
including controlled examples for input that cannot support an explanation.
The guide also notes that schema-conforming outputs can contain mistakes, which
is why prose quality is evaluated separately from the response boundary.
