# Progressive repair hints

The portal shows the first deterministic repair operation after each successful
check. A **Show next hint** button reveals exactly one additional operation in
the existing, ordered trace. The trace and distances are unchanged.

## Contract

- A new successful check starts with `min(1, operationCount)` visible hints.
- A click reveals one more hint, in order, without another backend, solver, or
  provider request. The count identifies how many hints remain.
- Only revealed operations and their matching Luna descriptions are inserted
  into the document. Stable `operation-N` identifiers preserve exact source and
  canonical locators; revealing a hint does not change the active highlight.
- The full Luna summary is withheld while operations remain unrevealed, because
  it may describe later repairs. Per-instance explanations remain available.
  When all hints have been revealed, the checked summary becomes available.
- An edit, exercise change, comparison-method change, reset, pending check,
  invalid check, or stale response discards the previous reveal count. Stale
  buttons cannot reveal operations for a different draft.
- With zero operations there is no reveal button, the existing no-edits message
  remains, and Luna's alternative-solution encouragement is available.
- The control is keyboard accessible, has an explicit ordered-list target and
  announces the current count. The final click leaves a disabled completion
  control so keyboard focus is retained.

## Verification

The focused browser harness checks canonical and AST traces, one-by-one reveal,
Luna descriptions and summary gating, exact locators after reveal, resets on
draft/metric/exercise/check changes, delayed responses, empty traces, and that
revealing does not trigger API work. It uses a private ephemeral local port and
deterministic responses; it does not use the live preview or OpenAI credentials.
