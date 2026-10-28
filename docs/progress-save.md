# Saving progress and opening dependent practices

Drafts are saved automatically in this browser. The editor says **Saved in this
browser** when that write succeeds. **Export progress** downloads a
portable JSON file with drafts, last verified implementations, bounded edit
history, solved badges and editor preferences. **Import progress** imports it
without starting analysis or AI calls. Save a fresh copy after important changes,
especially if the page reports that browser storage is unavailable.

**Reset** restores the exercise's starter predicate. **Undo reset** retrieves the
exact previous draft and saves it again through the usual editor path. This
one-step recovery remains available until you edit the code or change exercises;
it is not a permanent revision history. Export a backup before leaving a draft
you want to keep for later.

The tutor has separate recovery controls. **New chat** starts a fresh server
conversation while preserving any unsent question. **History** lets you read and
select text from earlier chats in the same tab and exercise. It keeps at most
five conversations within a shared 64 KiB text budget; older content may be
removed to fit. Reloading, changing the exercise, or changing its version or
prerequisites clears this local archive. Viewing a past chat does not send it to
a provider. **Continue this chat** selects an earlier conversation; the next
explicit send uses compressed excerpts from the backend's retained history,
along with the current question, code and available checks. Selecting Continue
itself makes no provider call. The excerpts retain message roles and draft
revision labels and are marked incomplete; old advice does not replace current
evidence. The backend retains only the latest six exchanges for thirty minutes.
If that history has expired, the local transcript can still be read, but it
cannot be resumed; start a fresh chat instead. Browser transcript text is never
accepted as server history. Reusing a saved user question fills an empty composer
for review; it does not send automatically. Tutor archives are not included in
exported progress files.

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
