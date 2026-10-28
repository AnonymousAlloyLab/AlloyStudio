# Learner-workspace UX review for v0.0.9-alpha.1

The visual review inspected the real `graphs-inv1` exercise on 2026-10-28 at
1440 × 1000 and 390 × 844, with chat both open and closed. It covered model
switching, an unsent message, code reload, Reset and desktop-to-mobile resizing.
The reviewer made two bounded suggestion requests. Source and interaction review
then checked the draft-preservation paths. This is a focused usability review,
not a user study or an AI guidance-quality benchmark.

## Findings and changes

1. **Reset could erase the only saved learner draft.** The starter replaced the
   editor body and was immediately autosaved; a toast offered no way back.
   Reset now exposes **Undo reset** for the exact pre-reset body. The backup is
   limited to the current exercise/version until the next edit or navigation,
   so an old recovery action cannot overwrite work in another exercise.
2. **Several chat actions could discard written text.** A failed reply replaced
   a follow-up typed while waiting; a suggestion replaced an unsent question;
   New chat cleared the composer. These actions now preserve the unsent draft.
   Session expiration keeps the unanswered question visible for manual reuse
   and invalidates old capabilities without an automatic retry.
3. **Starting a fresh chat made previous advice inaccessible.** The added
   **History** control keeps a bounded, read-only local archive after New chat.
   Its five-conversation/64 KiB budget, exercise boundary and tab lifetime are
   disclosed. Viewing or copying old text does not call a provider. An explicit
   **Continue this chat** action selects retained backend history for the next
   send. The backend compresses its own messages into bounded, incomplete
   excerpts with role and revision labels; current learner evidence remains
   authoritative. It never accepts the browser transcript as history. If the
   backend token has expired, the local transcript stays readable but cannot
   be resumed. Reusing a user question requires an empty composer and a later
   explicit send.
4. **Model settings lacked context.** The composer grouping was useful, but
   reasoning tiers had no explanation and an explicit model choice reverted
   on reload. Short effort guidance now accompanies the controls. Only model
   and effort IDs are remembered; each load validates them against configured
   choices. Conversation text and tokens are not stored with that preference.
5. **The empty tutor offered little direction while suggestions loaded.**
   Immediate starting points offer three learning approaches: understand the
   requirement, interpret one hint, or examine an example. They fill an empty
   message box without sending or making a model call. They share the existing
   three-item slot with AI-generated recommendations.
6. **Saving and responsive navigation were ambiguous.** “Draft saved” and
   “Save progress” did not distinguish browser persistence from a portable
   backup. The UI now says **Saved in this browser**, **Export progress** and
   **Import progress**. Resizing into the horizontal exercise strip also keeps
   the current exercise visible; a fresh mobile load already behaved correctly.
7. **Later layout changes could hide the newest tutor reply.** A compression
   notice or a longer set of suggestions could shrink the conversation area
   after it had scrolled to the reply. The panel now follows the tail when the
   reader is already there, while preserving a reader's position in older text.

Independent review constructed a second history-transfer case: after an initial
compressed resume, a recommendation could reattach more than 100 KB of full old
turns. With a valid 130 KB question, a later reply also exceeded the request
budget. Resumed conversation branches now retain abridged mode for subsequent
replies and recommendations. The regression verifies the 6,000-byte history
boundary, current-context precedence, and unchanged original conversation branch.

The initial review confirmed that ordinary code reload and model switching
preserved learner drafts, and that the revised desktop editor/trace/chat layout
did not overlap. Those working paths remain part of the regression scope.

## Verification and limits

The isolated release candidate passed **1,725 Python tests, 500 engine checks,
194 portal browser scenarios and 7 dashboard scenarios**. Browser coverage
includes 31 tutor scenarios and 12 navigation/recovery scenarios. Final real-page
inspection covered 1440 × 1000, 390 × 844 and 320 × 568. Three real Luna low-effort
replies and six suggestion calls verified recovery, archive continuation and
current-context behavior. Viewing History and selecting Continue made zero
provider requests; only the explicit follow-up sent the resume token.

After the final scrolling fix, the real conversation measured 635 px of content,
a 280 px viewport and scroll position 355 px after recommendations settled: the
newest reply remained visible. Pixel inspection found no remaining overlap or
horizontal overflow in the checked layouts. A 19-second real-page walkthrough
and final desktop/mobile screenshots are retained locally.

Acceptance checks cover exact draft recovery and stale-action rejection,
suggestion/New chat preservation, archive bounds and context clearing, explicit
continuation with compressed backend history, expiry handling, absence of
provider calls when viewing history or choosing a starting point, validated
model preferences, keyboard navigation, desktop/mobile layouts and packaging.
Final walkthrough screenshots come from the live page rather than synthetic
provider-response fixtures. Local captures are held under
`build/ux-alpha1/screenshots/` and are not automatically public release assets.

Browser drafts depend on storage availability. Undo reset is one-step recovery,
not a durable version history. Tutor archives are temporary tab memory and are
excluded from progress exports. Resumable backend history is separately limited
to six exchanges and thirty minutes; compression is not a complete record.
Higher reasoning effort may take longer; it is
not a guarantee of answer quality. The tutor's existing hidden-answer boundary
and exact-evidence checks still apply. No new Lean closure claim is made here.
