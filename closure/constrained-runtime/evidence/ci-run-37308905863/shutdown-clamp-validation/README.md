# Shutdown allowance clamp validation

This append-only supplement records the constructed rounding counterexample and validation of the production fix for the Windows failure in run [37308905863](https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37308905863). The original hosted Windows failure reported `65.00000000000006` against an unchanged maximum of `65`; the deterministic local coarse-clock witness reproduces the same breach with `65.00000000000001`.

The production fix clamps every remaining shutdown-stage allowance to `[0, 65]` while retaining the common deadline. The final Python 3.11.16 validation passed 71 tests, including coarse-clock rounding, the exact deadline, expired budgets before and between stages, and the existing progressing-clock case. Existing assertions were not relaxed. The successful local test log contains registered test names and outcomes only.

The before and after witness files are copied unchanged. The earlier after-witness binds a previous test snapshot (`233deff583ca671bef3ca17543e65050caa84d74f273b3607ef1a7cb933ec30a`); the final validation summary binds the completed test source, including the exact-deadline case. Its production hash is unchanged. `manifest.json` records the final source hashes and hashes of this supplement's files.

The preceding master and native IIS successes tested commit `76db3e720abd217ba098efcc63c9cb099b947e82`, before this clamp. They do not validate the newly changed production source. These local regression results likewise do not replace a fresh native Windows/IIS or full CI run. No raw hosted CI logs or private configuration are included. The parent evidence directory and its existing manifest remain unchanged.
