# Native IIS second failed run

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37298691619
Revision: 6fce375c982c8bb166dabba45c447a730eba6573

The native report records two Python and two Java application matches. Scalar selection and Python execution passed, as did private packaging, extraction, creation of two distinct sites, and unoccupied listener checks.

Installation failed at Manage-AlloyStudio.ps1 line 120 before task registration. The retained log shows json.load(sys.stdin) failing with JSONDecodeError at the first input character in the inline Python network-policy validator. The log does not contain the pipe bytes, so it does not establish whether stdin was empty or encoded unexpectedly; no byte-content claim is made.

The patch replaces inline Python source and JSON stdin transport with run_backend.py --check-network-policy BACKEND_ROOT and repeated address arguments. It invokes the identical traffic_identity validators before task/ACL mutations. Regression tests exercise empty/nonempty valid policies, exact IPv6 addresses, malformed/noncanonical networks, option-shaped values, missing configuration, unexpected mode mixing, spaces in the backend path, invalid stdin, and absence of generated files. No private configuration is required or read by preflight.

Local IIS launcher tests: 13 PASS; IIS connection tests: 8 PASS. Native Windows rerun remains necessary. No complete Windows acceptance pass is claimed for this failed run.
