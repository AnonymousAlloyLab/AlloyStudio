# Saving progress and opening dependent practices

Drafts are saved automatically in this browser. **Save progress** downloads a
portable JSON file with drafts, last verified implementations, bounded edit
history, solved badges and editor preferences. **Load progress** imports it
without starting analysis or AI calls. Save a fresh copy after important changes,
especially if the page reports that browser storage is unavailable.

A dependent practice lists the predicates it requires and offers buttons to
open them. Complete each prerequisite with zero Canonical or AST distance and
a behavioral score of exactly 1.000. The backend also requires completed checks
with no undercoverage or overcoverage, agreeing samples and both agreeing
instance categories. A saved green badge alone cannot unlock another practice.
Canonical remains the default.

The model environment inserts your last verified prerequisite implementations.
An unverified draft does not replace them. Changing a verified implementation or
its prerequisite context invalidates downstream checks; verify the changed
implementation and then work through the dependent practices again.

Completion receipts are private, bounded backend memory, expire after 24 hours
and disappear when the backend restarts. They contain no code or oracle. Loading
progress on another deployment or after expiry preserves saved code and history,
but **Restore checks** must revalidate last verified implementations before
unlocking their dependents. Restoration runs normal feedback and behavioral
checks sequentially, in prerequisite order, with no Luna calls. **Cancel restore**
stops further requests. Difficult or changed solutions can fail or time out just
as in normal analysis; their dependent practices remain locked.

Files are limited to 4 MiB, 200 exercise records, 8 KiB per predicate body and
12 history entries per distance mode. Invalid files are rejected before changing
saved state. Removed or changed exercise versions are omitted and reported.
Files include your own code and progress, so share them only as intended. Browser
storage is local to the browser origin; saving a file provides portability.

See the [implementation acceptance contract](practice-dependencies-progress-spec.md)
for exact limits, receipt provenance and trust boundaries. This feature adds no
new Lean closure claim.
