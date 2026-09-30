# Instance visualization audit for v0.0.3-alpha

Completed 2026-09-30T22:28:16.662Z. This audit covers all **181 catalogue invariants** with real, public `/api/behavior` responses, the production JavaScript renderer, and Chromium. No mock instances or model-provider calls were used.

**181/181 invariants have passing rendering checks** for the recorded drafts. The original catalogue starters produced usable responses for 177/181 invariants. The 4 remaining starters are empty: `cv_v1-inv1`, `cv_v1-inv3`, `cv_v1-inv4`, `productionLine_v1-inv1`.

The portal correctly rejected those empty inputs. Each was tested separately with the explicit learner body `no none` (a constant-true predicate), preserving its model environment and hidden oracle. These supplementary probes test the renderer on those models; they are not successful evaluations of the empty starters and are not hint-quality observations.

The final pass checked **1,342 concrete instances**, containing **1,439 serialized states**, at both **1440 px desktop** and **390 px mobile** widths: **2,878 state/viewport checks**. Of the states, **1,413 contain objects or relation values** and **26 are empty states**, for which the explicit empty-state description was checked rather than claiming a drawable graph.

Observed failures: **0 geometry/semantic failures**, **0 browser errors**. No returned state required diagram truncation. The four categories had these solver statuses: sat: 460, unsat: 264.

## What was checked

- Every available example (up to three in each category), including every returned temporal state, was rendered.
- Independent browser geometry checked object/object, text/text, text/object, connection/unrelated-text, and connection/unrelated-object intersections; clipped or invisible labels; and labels escaping their own object.
- Public tuple data was independently compared with rendered atom identities, signature membership, directed binary endpoints, ordered n-ary columns, repeated endpoints, and duplicate/collapsed paths. Under the display caps, all public atoms and tuples had to appear.
- Counts were checked against the 40-object/48-tuple display caps, and a limitation notice had to match whether data was omitted. None of these actual states exceeded those caps, so the positive truncation-notice path is covered by the separate dense-fixture browser regressions, not this corpus sample. Empty states had to retain an honest description. Mobile overflow had to remain inside the diagram, with a complete object initially visible when objects exist.
- All 181 invariant IDs were asserted; source, compiled class, bundled JAR, and SQLite database hashes were frozen and checked unchanged at completion. The actual headless browser version was recorded. Its binary hash is separate post-run provenance: Playwright's initial executable-path hash refers to installed full Chromium, while default headless execution uses headless-shell. The server used one worker; solver requests were sequential; the default behavioral time limit was 30 seconds.

## Evidence and reproduction

- [Public per-invariant summary and source/runtime hashes](benchmarks/instance-audit-v003.json). This includes category availability, starter/supplementary-draft provenance, screenshot hashes, and every invariant's check count.
- Local full evidence: `build/instance-audit-v003/manifest.json`, `responses/`, and `images/`. These retain only the public behavioral response plus hashes/provenance; no oracle predicate or credential is copied into this report.
- Contact sheets: `build/instance-audit-v003/contact-sheets/instance-audit-v003-contact-sheet-01.jpg` through `-06.jpg`; all 181 screenshots appear once. These are thumbnail overviews of actual browser screenshots, not diagrams invented for the report. A screenshot captures one returned state per invariant; the numerical checks cover every returned state.
- The release evidence ZIP includes the public summary, all 181 individual screenshots, and six contact sheets; detailed local responses remain outside the release archive.

From the repository root, after the normal engine/database setup:

```bash
node tests/instance-catalogue-audit.mjs
python3 scripts/summarize_instance_audit.py
```

The second command requires Pillow to assemble the screenshot contact sheets. Set `ALLOY_INSTANCE_AUDIT_OUTPUT` for a distinct audit directory; pass the same directory via `--input` to the summarizer. The collector owns its scratch directory beneath that output, disables OpenAI, starts and stops a fresh local backend, and only reuses a cached response when its request, source, and runtime hashes match.

## Interrupted run and recovery

An earlier attempt completed 133 invariants before Chromium reported `Target crashed` while starting `trainStationOld-inv9`. A second attempt with fresh pages failed after seven invariants. Its captured browser stderr reported failure to create shared memory beneath system `/tmp`: `No space left on device (28)`. The kernel logged an ext4 directory-index limit at both failure timestamps; its inode 655363 matches `/tmp`. This was the directory htree capacity limit, even though filesystem blocks and inodes remained available. The application cgroup recorded zero OOM and OOM-kill events. The interrupted manifests, logs, and filtered kernel evidence remain in the local audit directory, with public diagnosis and evidence hashes in the summary.

The collector now sets `TMPDIR`, `TMP`, and `TEMP` for both the parent Playwright process and Chromium to its owned build scratch directory, and uses native shared memory instead of Chromium's fallback to system `/tmp`. No user temporary files were deleted. It also uses a fresh browser page/context per invariant, records the active state/viewport, and keeps browser process logs. All 181 rendering checks were rerun against preserved, hash-matched real solver responses or newly collected responses. Its zero-error result is specific to that completed run; it does not erase the two infrastructure failures or establish a long-session browser stability guarantee.

An independent diagnostic replay rendered the saved `trainStationOld-inv9` response's eight actual states 640 times in one page without a crash. At each 80-render sample, retained DOM counts were stable (one document, 82 nodes, five listeners); measured JavaScript heap grew from 1,083,216 to 1,123,172 bytes and the last two samples were equal. This probe forced garbage collection every 80 renders, omitted screenshots/geometry checks and native compositor-memory measurements, and recorded runtime hashes retrospectively. It is bounded supporting evidence against retained DOM/listener growth, not a full workload reproduction or independent proof of the filesystem cause. Measurement/provenance hashes are in the public summary; local details remain in `build/instance-audit-v003/crash-replay/`.

## Limits

This is a finite regression audit of the returned, bounded Alloy instances. It does not prove unbounded model correctness, enumerate all satisfying instances, measure learning outcomes, or establish that every possible larger graph will be visually pleasing. Unsatisfiable categories legitimately have no images. Connections may cross other connections; the checked exclusion is collisions with unrelated objects and text. Empty starters and the four supplementary drafts remain explicit in every denominator.

## Per-invariant coverage

| Invariant | Draft | Instances | Drawable / empty states | Viewport checks | Result |
|---|---|---:|---:|---:|---|
| `classroom_fol-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv6` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv8` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv9` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv10` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv11` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv12` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_fol-inv13` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv14` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_fol-inv15` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv3` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv6` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv8` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv9` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv10` | starter | 6 | 6 / 0 | 12 | passed |
| `classroom_rl-inv11` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv12` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv13` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv14` | starter | 9 | 9 / 0 | 18 | passed |
| `classroom_rl-inv15` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesNew-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesNew-inv3` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv10` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv11` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv12` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv13` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv14` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesNew-inv15` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv10` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv11` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv12` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv13` | starter | 6 | 6 / 0 | 12 | passed |
| `coursesOld-inv14` | starter | 9 | 9 / 0 | 18 | passed |
| `coursesOld-inv15` | starter | 6 | 6 / 0 | 12 | passed |
| `cv_v1-inv1` | `no none` supplement; empty starter rejected | 6 | 6 / 0 | 12 | passed |
| `cv_v1-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `cv_v1-inv3` | `no none` supplement; empty starter rejected | 6 | 6 / 0 | 12 | passed |
| `cv_v1-inv4` | `no none` supplement; empty starter rejected | 6 | 6 / 0 | 12 | passed |
| `cv_v2-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `cv_v2-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `cv_v2-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `cv_v2-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `graphs-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `graphs-inv2` | starter | 9 | 8 / 1 | 18 | passed |
| `graphs-inv3` | starter | 6 | 5 / 1 | 12 | passed |
| `graphs-inv4` | starter | 9 | 8 / 1 | 18 | passed |
| `graphs-inv5` | starter | 6 | 5 / 1 | 12 | passed |
| `graphs-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `graphs-inv7` | starter | 9 | 8 / 1 | 18 | passed |
| `graphs-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `lts-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `lts-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `lts-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `lts-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `lts-inv5` | starter | 6 | 6 / 0 | 12 | passed |
| `lts-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `lts-inv7` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLineNew-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLineNew-inv5` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLineNew-inv7` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv8` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLineNew-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLineNew-inv10` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v1-inv1` | `no none` supplement; empty starter rejected | 6 | 6 / 0 | 12 | passed |
| `productionLine_v1-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v1-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v1-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v2-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v2-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv3` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv5` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v2-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv7` | starter | 6 | 6 / 0 | 12 | passed |
| `productionLine_v2-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `productionLine_v2-inv10` | starter | 6 | 6 / 0 | 12 | passed |
| `socialMedia-inv1` | starter | 9 | 9 / 0 | 18 | passed |
| `socialMedia-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `socialMedia-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `socialMedia-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `socialMedia-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `socialMedia-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `socialMedia-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `socialMedia-inv8` | starter | 6 | 6 / 0 | 12 | passed |
| `trainStationNew-inv1` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv3` | starter | 6 | 6 / 0 | 12 | passed |
| `trainStationNew-inv4` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv6` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationNew-inv10` | starter | 9 | 9 / 0 | 18 | passed |
| `trainStationOld-inv1` | starter | 7 | 8 / 0 | 16 | passed |
| `trainStationOld-inv2` | starter | 4 | 5 / 0 | 10 | passed |
| `trainStationOld-inv3` | starter | 4 | 7 / 0 | 14 | passed |
| `trainStationOld-inv4` | starter | 7 | 10 / 0 | 20 | passed |
| `trainStationOld-inv5` | starter | 4 | 7 / 0 | 14 | passed |
| `trainStationOld-inv6` | starter | 4 | 4 / 0 | 8 | passed |
| `trainStationOld-inv7` | starter | 9 | 14 / 0 | 28 | passed |
| `trainStationOld-inv8` | starter | 4 | 7 / 0 | 14 | passed |
| `trainStationOld-inv9` | starter | 7 | 8 / 0 | 16 | passed |
| `trainStationOld-inv10` | starter | 7 | 10 / 0 | 20 | passed |
| `trainStationOld-inv11` | starter | 9 | 13 / 0 | 26 | passed |
| `trainStationOld-inv13` | starter | 7 | 13 / 0 | 26 | passed |
| `trainStationOld-inv14` | starter | 4 | 7 / 0 | 14 | passed |
| `trainStationOld-inv15` | starter | 9 | 14 / 0 | 28 | passed |
| `trainStationOld-inv16` | starter | 7 | 13 / 0 | 26 | passed |
| `trainStationOld-inv17` | starter | 5 | 6 / 1 | 14 | passed |
| `trash_fol-inv1` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_fol-inv2` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_fol-inv3` | starter | 2 | 2 / 0 | 4 | passed |
| `trash_fol-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_fol-inv5` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_fol-inv6` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_fol-inv7` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_fol-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_fol-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_fol-inv10` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_ltl-inv1` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_ltl-inv2` | starter | 9 | 8 / 6 | 28 | passed |
| `trash_ltl-inv3` | starter | 9 | 8 / 6 | 28 | passed |
| `trash_ltl-inv4` | starter | 7 | 6 / 1 | 14 | passed |
| `trash_ltl-inv5` | starter | 7 | 9 / 1 | 20 | passed |
| `trash_ltl-inv6` | starter | 9 | 15 / 0 | 30 | passed |
| `trash_ltl-inv7` | starter | 9 | 11 / 1 | 24 | passed |
| `trash_ltl-inv8` | starter | 9 | 12 / 0 | 24 | passed |
| `trash_ltl-inv9` | starter | 9 | 11 / 1 | 24 | passed |
| `trash_ltl-inv10` | starter | 6 | 9 / 0 | 18 | passed |
| `trash_ltl-inv11` | starter | 9 | 12 / 0 | 24 | passed |
| `trash_ltl-inv12` | starter | 9 | 8 / 3 | 22 | passed |
| `trash_ltl-inv13` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_ltl-inv14` | starter | 9 | 12 / 0 | 24 | passed |
| `trash_ltl-inv15` | starter | 9 | 12 / 0 | 24 | passed |
| `trash_ltl-inv16` | starter | 6 | 9 / 0 | 18 | passed |
| `trash_ltl-inv17` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_ltl-inv18` | starter | 6 | 9 / 0 | 18 | passed |
| `trash_ltl-inv19` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_ltl-inv20` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_rl-inv1` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_rl-inv2` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_rl-inv3` | starter | 7 | 6 / 1 | 14 | passed |
| `trash_rl-inv4` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_rl-inv5` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_rl-inv6` | starter | 6 | 6 / 0 | 12 | passed |
| `trash_rl-inv7` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_rl-inv8` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_rl-inv9` | starter | 9 | 9 / 0 | 18 | passed |
| `trash_rl-inv10` | starter | 6 | 6 / 0 | 12 | passed |
