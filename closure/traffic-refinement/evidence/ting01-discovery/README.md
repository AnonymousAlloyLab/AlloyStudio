# TRF-01 decoding discovery (finite executed counterexamples)

The script `decode_witnesses.py` ran on the source hashes in
`decode-witness-results.json`, with OpenAI disabled, without importing private
configuration or constructing Portal/JVM engines. Actual production server and
HTTP reader code handles loopback sockets. Six source inputs are retained in
`frozen-sources/`; the two Python standard-library modules are hash identified.
No production file was changed by this investigation.

## Confirmed deadline breaches

1. **Buffered header reads bypass expiration.** With `DeadlineReader` clock
   initially 0 and the default deadline 5, the first `readline()` receives the
   complete request in one recv. Set the injected clock to 6: the second
   `readline()` returns `Host: localhost\r\n` instead of rejecting. This is a
   deterministic minimal unit witness. The actual socket counterpart pauses
   after the first request-line return for 80 ms while using a 40 ms header
   budget; `/api/health` still returns 200. The pause represents legal OS
   preemption, not a modification to decoder output.
2. **No completion check after JSON decoding.** A valid `{}` request to
   `/api/channel` uses a 40 ms body budget. The actual `bounded_json` is called,
   then its caller is paused for 80 ms before the valid result is returned.
   The real handler calls `scheduler.issue_channel` and returns 200. This
   controlled scheduling witness establishes the missing final clock check;
   it is not a claim that parsing this small input naturally consumes 80 ms.

## Request grammar gaps relevant to a later strict decoder subclaim

The actual handler returns a successful health response for an embedded bare
CR in `GET\r /api/health HTTP/1.1\r\n`, for tab-separated request tokens,
for HTTP/1.1 without Host, for a legacy HTTP/0.9 request, and for an absolute
request target whose authority disagrees with Host. The first is a definite
strict request-line framing defect; exact supported versions, target forms,
and Host constraints should be frozen explicitly before proving the rest.

Conflicting Content-Type parameters (`charset=utf-8; charset=latin-1`) pass
and issue a channel. The decoder takes the first charset. Reject duplicate
parameters or specify a deliberately narrower grammar in a later subclaim.

## Negative control and harness history

EOF after `GET /api/health HTTP/1.1\r\nHost: localhost\r\n` is already
rejected with 400. `DeadlineReader` rejects the empty EOF line as a malformed
header. The initial harness incorrectly expected every discovered case to
succeed; its result is retained as `initial-harness-result.json`. The final
harness explicitly asserts this is a negative control and exits successfully.
Do not describe header-termination failure as a confirmed production defect.

## Minimal TING01 implementation correspondence decomposition

- Closed byte-buffer read state: header mode, remaining bytes/count, buffer,
  absolute deadline, completion flag. Every accepted return checks expiration;
  only an explicit CRLF blank line establishes header completion.
- Request parser handoff: after stdlib parsing, require the completed-header
  flag and check the same deadline before route dispatch. Legacy HTTP/0.9
  request lines cannot bypass the chosen grammar or termination rule.
- Header-to-body transition: check the original header deadline before
  installing a distinct fixed body deadline; do not renew on progress.
- Body completion: exact declared-byte count, bounded JSON call, then clock
  check before the decoder's validated result can authorize business work.
- A distinct later subclaim must prove strict complete request-line/header/
  UTF-8/JSON byte decoding and the admission/lane state transitions. TING01
  alone must not close original TRF-01.

Recommended targeted tests: extend `tests/test_traffic_http.py` with fake-clock
buffered-read and deterministic preemption controls; retain EOF rejection as
negative control; test final header parse check and begin_body transition;
add the post-bounded_json check for public and admin body callers (shared
implementation). Existing admin absolute-body and slow-trickle tests should
remain unchanged. Use operation-boundary clock events in the Lean model;
Python monotonic scheduling/clock behavior remains an explicit runtime TCB.

## Follow-up repaired-source tests

`tests/test_ingress_deadlines.py` adds 19 focused regressions. The final
source-bound execution is `post-repair-deadline-tests.json` and its text log;
all 19 pass. Source files were hashed before and after execution and matched.
The proof/test module itself is retained in the repository, not this discovery
archive. `pre-repair-deadline-tests.txt` is an initial expected-failure run of
an earlier test draft, including a fake-clock origin mismatch in two tests;
this is not the final source binding. `pre-repair-deadline-tests-v2.txt` was
actually executed after production repairs appeared concurrently and should
be treated only as an intermediate passing run despite its temporary name.
The final report is the authoritative repaired-source run.

The new single-sample transition test confirms `begin_body` derives its new
body deadline from exactly the clock sample checked against the old header
deadline. A hypothetical next clock sample beyond the old deadline is not
consulted to mint a later body deadline.
