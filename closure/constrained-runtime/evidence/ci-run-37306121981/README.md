# Retained passing CI 37306121981

[GitHub Actions run](https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37306121981)
completed successfully at revision `76db3e720abd217ba098efcc63c9cb099b947e82`.
Linux passed 1,233 Python tests, 378 engine checks, 86 public browser checks and
seven dashboard checks, plus offline LP05 replay and historical-source freshness
reporting. Windows and macOS portable jobs also passed.

This is the run before the later shutdown-clamp change. The duplicate tagged
Windows run subsequently exposed a floating-point boundary, retained separately
in `../ci-run-37308905863/`. This pass does not erase that failure or validate a
later source revision. The dashboard snapshot reports `dirty: true` and every
`current` flag false; these original flags are preserved, not promoted to a clean
current-source certificate. The captured artifact does not identify dirty paths.

Only original sanitized dashboard JSONs and public run/job/step metadata are
included. No raw logs, private model bodies, configuration or IIS ZIP is retained.
The manifest hashes the payload files; historical evidence is unchanged.
