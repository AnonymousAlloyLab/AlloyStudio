# TRF-01 capacity and private ingress discovery

This is finite discovery evidence, not a closure report. Production files were not modified. `result.json` binds source hashes used by `witness.py`. The witness imports `traffic_http` and its dependency modules only, never instantiates the portal, opens a listener, accesses a corpus or credentials, or launches a JVM.

## Observed production boundary

- `Admission.reserve` holds `self.lock` while checking duplicate owners, capacity, lane/global/source tokens and acquiring the ownership reservation. `active` and `owners` increase in the same critical section. `release` removes only the exact owner and decrements once. Scheduler order is supplied by the Python lock TCB.
- `BoundedHTTPServer.process_request` calls the admission gate with the accepted socket as owner before `ThreadingMixIn.process_request` allocates the request thread. Capacity/rate refusal sends a bounded constant response or closes, and does not invoke that allocator.
- The exception branch releases if the stdlib allocator raises. `process_request_thread` releases in `finally`, including handler failure. The socketserver caller owns final socket shutdown; production stdlib semantics must be explicit in the TCB or separately bound.
- A single public listener and single control listener hold distinct admission objects. Each lane is nonborrowable. With the same profile and exactly that topology, separate bounds imply the sum bound arithmetically; a shared semaphore is unnecessary. This does not claim a bound over arbitrary additional manually constructed server objects or unrelated sites.
- Socket `accept` occurs in the listener thread before `process_request`; the reservation bound concerns allocated handlers. There may also be an accepted socket awaiting this callback plus the separately specified kernel backlog. TRF-03, not this handler bound alone, must account for all retained socket/native resources.
- Header deadline begins in `DeadlineReader.__init__`, called by `Handler.setup`, after the thread is allocated. Thus this source does not promise accept-to-setup wall-clock progress under scheduler starvation. The proof must define the deadline origin precisely and must not promise unconditional OS scheduling.

## Finite evidence

The executable checks 16 simultaneous attempts with a two-slot public lane, obtaining two reservations and 14 capacity rejections. A one-slot control lane is still independently available, giving three total reservations. Duplicate releases cannot release another owner. Distinct lane buckets both exhaust independently at fixed logical time.

Production callback spies check that ownership and active count already exist at allocator entry, that capacity rejection invokes no allocator, and that allocator/handler exceptions release. These are finite tests of actual production methods, not proofs of every Python schedule or stdlib implementation.

## Control-disabled boundary

The CLI parses `--control-port` with default `0`, and creates the private listener only under `if args.control_port`. TRF-00's frozen selected `direct-loopback-linux` profile also has `controlPort: 0`. Those facts are extracted and asserted by the witness. Consequently the exact default startup instance has no private diagnostic listener. Vacuous safety for a disabled lane does not establish that private ingress exists or survives public saturation.

To discharge the original TRF-01 control requirement without altering the historical TRF-00 evidence, freeze an explicit extension/operational topology for the enabled control listener, bind its one-public/one-control construction to the same profile and the literal loopback guard, and execute a real paired-listener saturation witness. Declare the successful constructor relation to the validated TRF-00 profile; do not reuse TRF-00's zero-control startup graph as evidence for an enabled listener. If control remains optional, state both enabled and disabled cases and avoid an availability claim for the disabled case. IIS/proxy exposure and platform deployment remain separate TRF-21 checks.

## Proposed finite semantic bridge

1. Use a lane-tagged state consisting of owner set, active count, lane capacity and token state. Extract the exact `reserve`/`release` critical-section transitions, not just textual anchor matches. Prove active equals owner count, release idempotence and active at most lane capacity inductively; rate refill accounting belongs to TRF-02.
2. Extract `process_request` as `reserve; reject-and-close OR allocate; on allocation exception release`, and `process_request_thread` as `invoke-handler; finally release`. Exclude arbitrary external mutation of admission internals; include the stdlib callback/lock contracts in the explicit TCB.
3. Define a topology constructor allowing at most one public lane and at most one control lane, using one detached normalized profile. Prove combined capacity and credits by componentwise sums. The enabled constructor must enforce literal loopback and a distinct port, and bind the actual `main` branches and profile equality.
4. Define the request lifecycle separately: socket accepted, lane reserved, handler allocated, header deadline installed, parsed and validated, body deadline installed where relevant, decoded, application operation dispatched, reservation released. Every resource/deadline proof needs its exact cut and exceptional exits. The request-decoder agent covers the known buffered-header and body-decode deadline gaps.
