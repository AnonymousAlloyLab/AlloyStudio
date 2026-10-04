# TRF-01 strict decoder contract

This contract and `strict-decoder-spec.json` precede the decoder repair. The six
constructed socket witnesses accepted by the original server are an embedded CR,
a TAB separator, HTTP/0.9, missing HTTP/1.1 Host, an absolute URI whose authority
mismatches Host, and duplicate conflicting charset parameters. The first, second,
fourth, fifth and sixth return 200; HTTP/0.9 returns headerless health JSON.

A request line has exactly three components separated by one ASCII space and one
final CRLF. Methods are HTTP tokens; supported routes retain GET and POST; HEAD retains its existing 501 response with no body.
Only HTTP/1.0 and HTTP/1.1 and a single-leading-slash origin target are accepted.
The target remains byte-for-byte unchanged: no fragments, spaces, controls,
backslash, invalid percent escapes or percent-encoded controls/backslash. Browser
percent-encoded UTF-8 paths and queries remain accepted. Unsupported methods keep
the existing method rejection route; no method is silently normalized.

Raw ordered header pairs preserve every occurrence. Token names and Latin-1
field values are checked before lookup; the socket reader separately enforces
CRLF, no obsolete folding and bounded lines, aggregate bytes and field count.
HTTP/1.1 needs one syntactically valid Host; HTTP/1.0 may omit it. Host cannot carry
userinfo, whitespace, a path, comma or out-of-range port. Relevant framing,
authorization and browser-origin headers are singletons. Any Transfer-Encoding
or Expect occurrence rejects, as does a non-identity content encoding.

A supplied Content-Type is application/json, optionally with one charset set to
UTF-8 or utf8, case insensitively and optionally quoted. Unknown parameters,
trailing semicolons and duplicate charset parameters reject. Mutations require
this Content-Type. Content-Length is a canonical nonnegative decimal of at most
eight digits; omitted length is zero. Nonmutation requests cannot carry a body.

A mutation reads exactly its positive route-bounded body size. JSON uses strict
UTF-8, rejects duplicate decoded keys at every depth, nonfinite values including
floating overflow, lone surrogates, over-depth containers and nonobject roots.
Route-specific schema validation remains between decode and engine/service
submission. Header/body completion still uses the existing sampled deadlines.

The proof boundary is the actual extracted guard program and closed regex
languages over raw input; it is not a theorem supplied a precomputed `valid`
Boolean. Ordinary Python execution, closed regular-expression execution,
standard IPv6 literal recognition, strict UTF-8 and JSON parsing/serialization
are specified runtime primitives in the TCB. Application guard ordering, syntax
constants and JSON policy hooks require mechanical source correspondence. Byte
budgets, deadlines, pre-handler allocation and lane separation are composed by
separate TRF-01 obligations. Tests are concrete witnesses, not universal proofs.

The source interpreter is `scripts/decoder_bridge.py`. It admits a closed Python
statement/call vocabulary, extracts the actual `_require` predicates and regex
constants, and emits `DecoderExtracted.lean`. The independent JSON specification
emits `DecoderContract.lean`. The model uses explicit lists of octets/codepoints,
including a regular-language derivative evaluator, to avoid importing unrelated
String-library proof axioms. `decoder_sound` proves every independent raw-input
rule from execution of the extracted program. `bounded_json` has a separate
closed AST mapping to its quoted-string depth scanner, recursive duplicate-key
checks, finite/Unicode serialization primitive and object-root predicate.

The narrow IPv6 and JSON primitives retain declared runtime trust. In particular,
the parsed JSON tree comes from successful standard JSON syntax parsing and a
successful strict UTF-8 decode; no theorem claims to replace those runtimes.
Its scalar serialization flag denotes only the standard finite-number and
Unicode encoding primitive. It supplies no evidence for request framing,
container depth, duplicate object keys or root type. Those checks remain explicit
in the model. JSON object keys additionally exclude lone surrogate codepoints.

Mutation tests build fresh Lean modules in network-isolated processes using the
already installed pinned binary. Removing a source origin-form guard or widening
the method regex changes extraction and fails the independent contract theorem.
Unsupported primitive substitutions and JSON policy changes fail the closed
interpreter. The recorded socket witnesses all reject before GET route dispatch;
valid browser and HTTP/1.0 requests preserve their established behavior, including
HEAD's existing 501 response with an empty body.
