# Authenticated import design review

Independent review completed before application implementation. Reviewed inputs:

| Input | SHA-256 |
| --- | --- |
| `docs/admin-security-spec.md` | `c645909897e9c620bcaef78033748b9f1ff660aab76dc91f53472db4daf021ff` |
| `closure/admin-spec.json` | `e456fa0bfcb808503d0060118f9cb1a7c5b3ef7056166b83d082c9c886a68da3` |

The review considered the existing HTTP handler, origin normalization, private
configuration handling, SQLite publication path, IIS rewrite rules and
configuration/package/closure exclusions. No live credential file was read.

Three specification gaps were resolved before implementation:

1. Empty setup input repeated twice could otherwise create an enabled account
   with a trivially guessable password. Setup now requires 12–1024 UTF-8 bytes,
   no NUL or invalid Unicode, matching confirmation and an 8-KiB login envelope.
2. Repeated malformed uploads could create arbitrarily many failed job records
   without creating any draft, bypassing a draft-only cap despite one worker.
   All job states now share the eight-global/two-per-session cap and 15-minute
   expiry; there is no separate unbounded terminal-job store.
3. A socket inactivity timeout permits a stalled peer to send a byte before
   each timeout indefinitely. The contract now specifies a five-second total
   request-body deadline, 60-second total parse/solver deadline, 256-MiB Java
   heap, 1-MiB provider response ceiling and a terminating 50-second provider
   subprocess deadline.

The following constructed transitions are also required implementation tests:

- Replace the password configuration while an old-password KDF is running: the
  completed old KDF must not create an authenticated session.
- Logout, expire or rotate configuration while upload work runs: publication
  must recheck authority and reject without changing the database. The final
  authorization lock must cover the SQLite commit against in-process logout.
  The documented external-filesystem atomicity limitation is accepted.
- Bootstrap anonymous state while an authenticated cookie is present: it must
  not destroy the authenticated session. Preauth and authenticated records are
  separate, bounded and expiring; failed requests must not renew idle lifetime.
- Supply duplicate Origin, Content-Length or session cookies, Transfer-Encoding,
  a wrong CSRF token, a forged forwarding header or missing mutation Origin:
  reject before body reads, parsing, solving or provider calls.
- Use the real IIS shape with HTTPS browser Origin and loopback upstream Host:
  production origin authority comes from private configuration. Loopback HTTP
  additionally requires loopback peer and matching literal Host.
- Attempt to retain, package or serve `admin.local.json`, drafts or upload
  witnesses publicly: private-config exclusions and exact public allowlists
  must prevent disclosure, including closure input snapshots.
- Return code fields, unknown names or source-like extra fields from Luna:
  accept no provider authority over source, grouping or equivalence. Public
  question prose still requires explicit administrator review.
- Import a late invalid variant or a dependency on a removed oracle, or race two
  commits using the same preview: reject atomically; publication is single-use.

The production cookie design is internally consistent: a host-only `__Host-`
cookie uses Secure and Path=/, with its name bound to configured origin and
application path. Cookie paths are not advertised as isolation from another
application on the same origin. The configured browser origin, TLS and Cloudflare
cache bypass are explicit deployment trust. Admin responses require private
no-store handling; local tests do not prove actual CDN or NTFS enforcement.

**Disposition: READY FOR IMPLEMENTATION against the hashes above.** No remaining
constructed design blocker was found. This review is not implementation or
unrestricted security verification. The registered executable tests, frozen
source and two clean offline builds remain required for finite closure.

After implementation, the register gained concrete test-prefix bindings without
changing the reviewed requirements. See `admin-implementation-review.md` for
constructed implementation findings and their regression tests. The review
hashes above identify the original preimplementation contract.
