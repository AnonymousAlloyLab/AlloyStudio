# Windows tag CI: observed shutdown allowance rounding breach

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37308905863
Revision: `76db3e720abd217ba098efcc63c9cb099b947e82`
Failed job: **Portable build (windows-latest)**, `111759198779`
Failed step: **Persistent transport, scheduling and HTTP boundary regressions**

The source-registered failing test is `test_traffic_portal.PortalLifecycleTests.test_unreaped_process_prevents_successful_shutdown_acknowledgement`. Its assertion at `tests/test_traffic_portal.py:140` observed an allowance of **65.00000000000006**, above the asserted maximum **65**. This is a concrete floating-point bound counterexample; it is not evidence of an unacknowledged process being released or a failure of the test's preceding drain-refusal assertion. The assertion is retained while the production allowance is corrected separately.

The numeric assertion and source identifier are narrowly projected from the completed job log. No traceback text, model input, dynamic subtest values, command output or raw log is stored here. The original safe dashboard JSONs and public Actions metadata are included unchanged.

At capture, the Windows job had completed with failure, macOS had passed, and the Linux job in this same workflow was still running. `run-at-windows-failure.json` deliberately preserves that partial snapshot; it is not a final workflow report. The source commit's earlier passing master run does not erase this later counterexample. The unpublished release remained on hold. Later completion evidence must be appended separately, without rewriting these files.

Scope: finite CI observation of a mocked shutdown-lifecycle unit test and a real floating clock; no universal timing or whole-program proof is claimed. The separate native IIS run and release-readiness result are separate gates.
