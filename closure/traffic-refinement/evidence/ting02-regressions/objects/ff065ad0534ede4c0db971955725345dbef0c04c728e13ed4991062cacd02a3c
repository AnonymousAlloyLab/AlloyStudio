# TRF-01: strict ingress and admission refinement

TING02 addresses the complete original TRF-01 obligation without rewriting its
statement or pass condition. Its independent [parent contract](../closure/traffic-refinement/strict-ingress-spec.json),
[decoder contract](strict-decoder-contract.md) and [admission contract](admission-contract.md)
are frozen before verification. The successor ledger links the authoritative
report; an earlier child result does not certify these changed production files.

The production repair validates the raw request line before the standard HTTP
parser can normalize it. HTTP/1.1 requires one valid Host field. Ambiguous
framing, transfer encoding, duplicate sensitive headers, unsupported content
encoding and conflicting JSON charset parameters are rejected. Public and
administrator bodies share exact-length, byte-limited, duplicate-free UTF-8 JSON
object decoding. Header, body and post-decoding completion retain sampled
absolute deadlines. Ordinary GET/POST behavior, Canonical as the default, both
hint metrics, complete correct pools and analysis algorithms are preserved.
HEAD retains the previous unsupported-method behavior.

Discovery reproduced six permissive-parser cases: bare CR and TAB request-line
separators, HTTP/0.9, a missing HTTP/1.1 Host, an absolute target with conflicting
authority, and repeated charset parameters. A separate scheduling witness held
thread teardown immediately after its reservation was released. With a limit of
one it produced two live request threads. The new bounded thread registry keeps
each socket's reservation until `is_alive()` reports that its thread has ended.
The listener reaps completed entries; it creates no additional reaper threads.
Failed construction before a start attempt releases its reservation. Once start
has been attempted, an exception may precede native bootstrap identification,
so the reservation remains until identification and termination are confirmed.
An attempt that never reaches bootstrap can consume one bounded slot until a
backend restart; it cannot free capacity for a still-pending native thread.

The first adversarial review constructed a model request with a POST method but
an independent `mutation=false` flag, bypassing body validation in the model.
The pinned kernel accepted that counterexample. The refined composition derives
method policy, raw header occurrences and byte counts from one wire value,
including the received terminating CRLF. UTF-8 and JSON outputs come from the
same body through explicitly named parsing primitives; callers cannot supply
unrelated decoded text or trees. A source-mutation witness also inserted an
unmapped `Handler.handle` override and issued a channel without receiving any
request bytes. The bridge now closes the complete handler and reader classes,
so new overrides cannot inherit the registered consumer mapping.

The proof has four connected parts. A closed decoder bridge translates actual
regexes and ordered validation rules into a Lean interpreter over octet lists.
An independently frozen grammar specifies acceptance. JSON nesting, duplicate
keys and object-root policies have separate correspondence to the production
scanner and parser hooks. The deadline bridge extracts actual guards and their
positions around reads, parsing and decoding. Admission proofs derive exact
owner counts and capacity bounds from empty state over transition histories,
including the thread registry lifecycle. The closed route bridge accounts for
all registered parser/read/route branches and binds the successful business
entry points to these validations. Unsupported syntax or a changed unregistered
call blocks the mapping instead of inheriting a previous proof.

The enabled topology has one public listener and one literal-loopback control
listener, with independent ownership, handler capacity and credits. The default
profile permits 30 public plus 2 control handlers, 60 plus 4 burst credits, and
30 plus 2 refill credits per second. No capacity is borrowed between lanes.
Control is optional and disabled by the default port 0; that branch claims no
private diagnostic availability. Serialized accept processing can retain at
most one accepted-but-unreserved socket per listener. Kernel listen queues are
separate OS resources, not handler reservations. Detailed refill histories
remain TRF-02; whole-process memory enforcement remains a later obligation.

Run `python3 scripts/verify_strict_ingress.py` for the registered gate. It freezes
all relevant source/dependency bytes, runs two clean Lean 4.34.1 builds inside
network-isolated namespaces, audits every project theorem and definition for
an empty transitive axiom set, checks production correspondence, executes real
socket and scheduling witnesses, and exercises source/proof mutation controls.
Six source-bound advisory reviews use two GPT-6 Luna, two GPT-6.1 Sol and two
GPT-6 Astra reviewers in sequence. Their findings require constructed
counterexamples. Review opinions do not determine kernel validity or closure.

`VERIFIED` means that the finite frozen claims passed under the declared trust
boundary. The Lean kernel/distribution, closed translators and audit code,
CPython primitive operations and standard-library exception/dispatch semantics,
thread/lock/socket behavior, monotonic clock ordering, Linux isolation,
filesystem, hardware and SHA-256 remain explicit trust. It is not an axiom-free
proof of the operating system or an arbitrary Python interpreter. A deadline
cut is the successful clock observation; scheduling delay after that sample is
not bounded by the theorem. Future revisions, native Windows/macOS, real IIS or
Cloudflare normalization, distributed deployments and unrelated traffic
obligations are not certified by this local ingress result.

Functional preservation is checked separately with the complete Python suite,
Java engine checks, browser scenarios, and a source-bound comparison of archived
HTTP/projector code against current one-shot and persistent modes. That bounded
comparison uses an isolated synthetic corpus, identical current engine binaries,
both feedback metrics, behavior examples, invalid drafts, Unicode, public views,
ETag revalidation, channels and worker reuse. It does not assert arbitrary-history
or all-model equivalence. Providers are disabled or mocked; no paid Luna calls
are part of these checks.
