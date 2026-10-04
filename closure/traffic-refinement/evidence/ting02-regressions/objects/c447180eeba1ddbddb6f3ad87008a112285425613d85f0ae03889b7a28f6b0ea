# Authenticated import implementation review

This records constructed counterexamples found after the specification-first
review. It is not a universal security or proof claim. The final closure report
must bind the actual source and executable regression results; this narrative
cannot discharge a failing test.

| Constructed input or transition | Refinement and executable witness |
| --- | --- |
| A parameterized `inv1C0[n: Node]` or function named `inv1C0` is silently treated as a helper, leaving only `inv1C1` checked. | Reserved Cvariant names must be parameterless predicates; `test_admin_upload` rejects both shapes. |
| An oracle calls a helper which calls another exercise's bare learner starter. | Traverse dependencies transitively; `test_admin_upload` rejects the context-dependent oracle. |
| Deleting an upload archive and clearing its preservation object leaves an exercise labeled administrator-reviewed. | Enforce distinct CLI-supplied and upload-reviewed provenance shapes; `test_admin_store` rejects the downgrade. Filesystem access capable of rewriting all provenance remains trusted. |
| Session expires during body transfer, or logout occurs after a job is queued but before its worker starts. | Recheck authority before admission and at worker entry; HTTP/service tests establish no solver/provider invocation after the constructed revocation. |
| Draft reaches age 901 seconds during transaction construction while its login session remains valid. | The final publication guard rechecks exact draft identity, revision, state and lifetime as well as authentication. Service regression advances the clock inside transaction work. |
| Null revision skips an optional freshness comparison. | Use a distinct internal sentinel for read-only lookup; mutation revisions must be exact integers, with null/bool/float/string negatives. |
| `/api/%61dmin/session` resolves to a session response but its raw path misses the private-header/CDN rule. | Reject encoded admin dispatch; all decoded admin-path error responses remain private/no-store. Actual HTTP test checks the encoded aliases. |
| A local provider fixture redirects to a second origin and urllib forwards its synthetic Bearer header. | A shared learner/admin transport refuses all redirects; two local servers verify the recipient receives no forwarded request or token. No real credential was used in the fixture. |
| Unsupported HEAD/OPTIONS/PUT requests inherit an HTML error without private cache headers. | Admin error handling emits sanitized 405 JSON with private/no-store headers and an empty HEAD body; actual HTTP regressions cover canonical and encoded paths. |
| A prior IIS installation retains read-only data ACLs when following the upgrade recipe. | The explicit `UpdateDataPermissions` action requires a stopped backend and migrates only the private exercise directory; code/configuration remain read-only, with script-contract regressions. |

Additional executed fixtures cover duplicate Alloy names accepted by the parser,
synthetic anonymous commands, private-modifier source spans, unused macros,
CRLF/Unicode spans, scope-limited equivalence counterexamples, late invalid
variants, facts nonvacuity, duplicate cookie/framing/JSON fields, total body-read
deadlines, password rotation during KDF, single-use publication, failed-job caps,
provider metadata rejection, byte-exact source witnesses, backup/restore and
fourteen offline browser workflows.

Alloy checks remain bounded; public question wording requires administrator
review. Native IIS/NTFS, Cloudflare policy, TLS, live OpenAI availability and
unbounded equivalence remain outside local verification. The new administration
implementation has no claimed end-to-end Lean refinement proof.
