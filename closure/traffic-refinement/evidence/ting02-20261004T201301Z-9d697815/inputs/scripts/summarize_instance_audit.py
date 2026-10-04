#!/usr/bin/env python3
"""Publish credential-free counts and contact sheets from the real instance audit.

The browser audit is the verifier. This script formats its completed evidence;
Pillow is used only to assemble already captured, unmodified browser screenshots.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, default=ROOT / 'build/instance-audit-v003')
    args = parser.parse_args()
    audit = args.input.resolve()
    manifest = json.loads((audit / 'manifest.json').read_text())
    rows = manifest['exercises']
    if len(rows) != 181 or not manifest.get('finished') or not manifest.get('inputsUnchanged'):
        raise SystemExit('Refusing to summarize an incomplete or unbound audit.')
    if len({row['id'] for row in rows}) != 181:
        raise SystemExit('Duplicate/missing invariant inventory.')
    for relative, expected in manifest['sourceHashes'].items():
        if digest(ROOT / relative) != expected:
            raise SystemExit('Source changed since audit: ' + relative)
    summary = {key: manifest[key] for key in ('schemaVersion', 'status', 'started', 'finished', 'protocol',
        'sourceHashes', 'runtimeHash', 'pythonVersion', 'javaVersion', 'chromiumVersion', 'catalogueIdsHash',
        'inputsUnchanged', 'counts')}
    summary['runtimeInventory'] = {
        'files': len(manifest['runtimeHashes']),
        'sqliteSha256': manifest['runtimeHashes']['exercises/exercises.sqlite3'],
        'classes': sum(name.endswith('.class') for name in manifest['runtimeHashes']),
        'jars': {name: value for name, value in manifest['runtimeHashes'].items() if name.endswith('.jar')},
        'note': 'Full per-class and installed full-Chromium binary hashes retained in the local manifest; default headless execution used headless-shell, distinguished below.'}
    launch_log = audit / 'browser-process.log'
    if launch_log.exists():
        launched = re.search(r'<launching> (.+?) --disable-field-trial-config', launch_log.read_text())
        if launched:
            executable = Path(launched.group(1))
            summary['actualBrowserProvenance'] = {'executable': executable.name,
                'version': manifest['chromiumVersion'], 'sha256': digest(executable),
                'hashTiming': 'Recorded after the completed run; this is not a before/after immutability claim for the headless-shell binary.',
                'launchLogSha256': digest(launch_log)}
    summary['manifestSha256'] = digest(audit / 'manifest.json')
    summary['priorInfrastructureEvents'] = manifest.get('priorInfrastructureEvents', [])
    diagnosis = audit / 'infrastructure-diagnosis.json'
    if diagnosis.exists():
        summary['infrastructureDiagnosis'] = json.loads(diagnosis.read_text())
    replay = audit / 'crash-replay/measured.json'
    if replay.exists():
        measured = json.loads(replay.read_text())
        provenance = audit / 'crash-replay/provenance.json'
        summary['separateResourceReplay'] = {'completedRenders': measured['completedRenders'],
            'states': measured['states'], 'crashed': measured['crashed'],
            'firstSample': measured['samples'][0], 'lastSample': measured['samples'][-1],
            'measurementSha256': digest(replay), 'provenanceSha256': digest(provenance),
            'limits': json.loads(provenance.read_text())['omissions']}
    summary['categoryStatuses'] = dict(Counter(category['status'] for row in rows for category in row['categories']))
    summary['limitedStateViewportChecks'] = sum(check['semantics']['limited'] for row in rows for check in row['checks'])
    summary['failures'] = sum(len(check['failures']) for row in rows for check in row['checks'])
    summary['browserErrors'] = sum(len(row.get('browserErrors', [])) for row in rows)
    summary['exercises'] = []
    screenshots = []
    for row in rows:
        checks = row['checks']
        record = {key: row[key] for key in ('id', 'title', 'status', 'starterStatus', 'draftKind', 'starterHash',
            'requestHash', 'responseHash', 'instances', 'states', 'drawableStates', 'emptyStates', 'categories')}
        record.update(stateViewportChecks=len(checks), failures=sum(len(check['failures']) for check in checks),
                      limitedStateViewportChecks=sum(check['semantics']['limited'] for check in checks))
        images = [check['screenshot'] for check in checks if check.get('screenshot')]
        record['screenshots'] = [{'path': image, 'sha256': digest(audit / image)} for image in images]
        summary['exercises'].append(record)
        if images:
            screenshots.append((row, audit / images[0]))
    from PIL import Image, ImageDraw, ImageFont
    sheet_dir = audit / 'contact-sheets'
    sheet_dir.mkdir(exist_ok=True)
    font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
    try:
        font = ImageFont.truetype(font_path, 15)
        small = ImageFont.truetype(font_path, 12)
    except OSError:
        font = small = ImageFont.load_default()
    sheets = []
    for offset in range(0, len(screenshots), 36):
        group = screenshots[offset:offset + 36]
        columns, cell_width, cell_height = 4, 400, 316
        sheet = Image.new('RGB', (columns * cell_width, ((len(group) + columns - 1) // columns) * cell_height + 54), '#e8edf3')
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 10), f'Actual Alloy instance screenshots: invariants {offset + 1}–{offset + len(group)} of {len(screenshots)}', fill='#18334a', font=font)
        draw.text((12, 32), 'Overview thumbnails only; every returned state was tested independently at desktop and mobile widths.', fill='#18334a', font=small)
        for index, (row, image_path) in enumerate(group):
            x, y = index % columns * cell_width, index // columns * cell_height + 54
            draw.text((x + 7, y + 5), row['id'], fill='#18334a', font=font)
            draw.text((x + 7, y + 24), f"{row['states']} states checked · {row['draftKind']}", fill='#18334a', font=small)
            thumb = Image.open(image_path).convert('RGB')
            thumb.thumbnail((cell_width - 12, cell_height - 52))
            sheet.paste(thumb, (x + 6, y + 44))
        filename = f'instance-audit-v003-contact-sheet-{offset // 36 + 1:02}.jpg'
        destination = sheet_dir / filename
        sheet.save(destination, quality=90)
        sheets.append({'path': 'contact-sheets/' + filename, 'sha256': digest(destination), 'exercises': [row['id'] for row, _ in group]})
    summary['contactSheets'] = sheets
    target = ROOT / 'docs/benchmarks/instance-audit-v003.json'
    target.write_text(json.dumps(summary, indent=2) + '\n')
    counts = summary['counts']
    unavailable = [row['id'] for row in rows if row['starterStatus'] != 'ok']
    lines = [
        '# Instance visualization audit for v0.0.3-alpha', '',
        f"Completed {manifest['finished']}. This audit covers all **181 catalogue invariants** with real, public `/api/behavior` responses, the production JavaScript renderer, and Chromium. No mock instances or model-provider calls were used.", '',
        f"**{counts['passed']}/181 invariants have passing rendering checks** for the recorded drafts. The original catalogue starters produced usable responses for {181 - counts['startersUnavailable']}/181 invariants. The {counts['startersUnavailable']} remaining starters are empty: `" + '`, `'.join(unavailable) + '`.', '',
        'The portal correctly rejected those empty inputs. Each was tested separately with the explicit learner body `no none` (a constant-true predicate), preserving its model environment and hidden oracle. These supplementary probes test the renderer on those models; they are not successful evaluations of the empty starters and are not hint-quality observations.', '',
        f"The final pass checked **{counts['actualInstances']:,} concrete instances**, containing **{counts['actualStates']:,} serialized states**, at both **1440 px desktop** and **390 px mobile** widths: **{counts['stateViewportChecks']:,} state/viewport checks**. Of the states, **{counts['drawableStates']:,} contain objects or relation values** and **{counts['emptyStates']:,} are empty states**, for which the explicit empty-state description was checked rather than claiming a drawable graph.", '',
        f"Observed failures: **{summary['failures']} geometry/semantic failures**, **{summary['browserErrors']} browser errors**. "
        + (f"The {summary['limitedStateViewportChecks']} limited state/viewport checks explicitly disclosed omitted diagram data. " if summary['limitedStateViewportChecks'] else 'No returned state required diagram truncation. ')
        + 'The four categories had these solver statuses: ' + ', '.join(f"{key}: {value}" for key, value in sorted(summary['categoryStatuses'].items())) + '.', '',
        '## What was checked', '',
        '- Every available example (up to three in each category), including every returned temporal state, was rendered.',
        '- Independent browser geometry checked object/object, text/text, text/object, connection/unrelated-text, and connection/unrelated-object intersections; clipped or invisible labels; and labels escaping their own object.',
        '- Public tuple data was independently compared with rendered atom identities, signature membership, directed binary endpoints, ordered n-ary columns, repeated endpoints, and duplicate/collapsed paths. Under the display caps, all public atoms and tuples had to appear.',
        '- Counts were checked against the 40-object/48-tuple display caps, and a limitation notice had to match whether data was omitted. None of these actual states exceeded those caps, so the positive truncation-notice path is covered by the separate dense-fixture browser regressions, not this corpus sample. Empty states had to retain an honest description. Mobile overflow had to remain inside the diagram, with a complete object initially visible when objects exist.',
        '- All 181 invariant IDs were asserted; source, compiled class, bundled JAR, and SQLite database hashes were frozen and checked unchanged at completion. The actual headless browser version was recorded. Its binary hash is separate post-run provenance: Playwright\'s initial executable-path hash refers to installed full Chromium, while default headless execution uses headless-shell. The server used one worker; solver requests were sequential; the default behavioral time limit was 30 seconds.', '',
        '## Evidence and reproduction', '',
        '- [Public per-invariant summary and source/runtime hashes](benchmarks/instance-audit-v003.json). This includes category availability, starter/supplementary-draft provenance, screenshot hashes, and every invariant\'s check count.',
        '- Local full evidence: `build/instance-audit-v003/manifest.json`, `responses/`, and `images/`. These retain only the public behavioral response plus hashes/provenance; no oracle predicate or credential is copied into this report.',
        '- Contact sheets: `build/instance-audit-v003/contact-sheets/instance-audit-v003-contact-sheet-01.jpg` through `-06.jpg`; all 181 screenshots appear once. These are thumbnail overviews of actual browser screenshots, not diagrams invented for the report. A screenshot captures one returned state per invariant; the numerical checks cover every returned state.',
        '- The release evidence ZIP includes the public summary, all 181 individual screenshots, and six contact sheets; detailed local responses remain outside the release archive.', '',
        'From the repository root, after the normal engine/database setup:', '',
        '```bash',
        'node tests/instance-catalogue-audit.mjs',
        'python3 scripts/summarize_instance_audit.py',
        '```', '',
        'The second command requires Pillow to assemble the screenshot contact sheets. Set `ALLOY_INSTANCE_AUDIT_OUTPUT` for a distinct audit directory; pass the same directory via `--input` to the summarizer. The collector owns its scratch directory beneath that output, disables OpenAI, starts and stops a fresh local backend, and only reuses a cached response when its request, source, and runtime hashes match.', '',
        '## Interrupted run and recovery', '',
        'An earlier attempt completed 133 invariants before Chromium reported `Target crashed` while starting `trainStationOld-inv9`. A second attempt with fresh pages failed after seven invariants. Its captured browser stderr reported failure to create shared memory beneath system `/tmp`: `No space left on device (28)`. The kernel logged an ext4 directory-index limit at both failure timestamps; its inode 655363 matches `/tmp`. This was the directory htree capacity limit, even though filesystem blocks and inodes remained available. The application cgroup recorded zero OOM and OOM-kill events. The interrupted manifests, logs, and filtered kernel evidence remain in the local audit directory, with public diagnosis and evidence hashes in the summary.', '',
        'The collector now sets `TMPDIR`, `TMP`, and `TEMP` for both the parent Playwright process and Chromium to its owned build scratch directory, and uses native shared memory instead of Chromium\'s fallback to system `/tmp`. No user temporary files were deleted. It also uses a fresh browser page/context per invariant, records the active state/viewport, and keeps browser process logs. All 181 rendering checks were rerun against preserved, hash-matched real solver responses or newly collected responses. Its zero-error result is specific to that completed run; it does not erase the two infrastructure failures or establish a long-session browser stability guarantee.', '',
        'An independent diagnostic replay rendered the saved `trainStationOld-inv9` response\'s eight actual states 640 times in one page without a crash. At each 80-render sample, retained DOM counts were stable (one document, 82 nodes, five listeners); measured JavaScript heap grew from 1,083,216 to 1,123,172 bytes and the last two samples were equal. This probe forced garbage collection every 80 renders, omitted screenshots/geometry checks and native compositor-memory measurements, and recorded runtime hashes retrospectively. It is bounded supporting evidence against retained DOM/listener growth, not a full workload reproduction or independent proof of the filesystem cause. Measurement/provenance hashes are in the public summary; local details remain in `build/instance-audit-v003/crash-replay/`.', '',
        '## Limits', '',
        'This is a finite regression audit of the returned, bounded Alloy instances. It does not prove unbounded model correctness, enumerate all satisfying instances, measure learning outcomes, or establish that every possible larger graph will be visually pleasing. Unsatisfiable categories legitimately have no images. Connections may cross other connections; the checked exclusion is collisions with unrelated objects and text. Empty starters and the four supplementary drafts remain explicit in every denominator.', '',
        '## Per-invariant coverage', '',
        '| Invariant | Draft | Instances | Drawable / empty states | Viewport checks | Result |',
        '|---|---|---:|---:|---:|---|',
    ]
    for row in rows:
        lines.append(f"| `{row['id']}` | {'starter' if row['draftKind'] == 'starter' else '`no none` supplement; empty starter rejected'} | {row['instances']} | {row['drawableStates']} / {row['emptyStates']} | {len(row['checks'])} | {row['status']} |")
    (ROOT / 'docs/instance-audit-v003.md').write_text('\n'.join(lines) + '\n')
    print(json.dumps({'status': summary['status'], 'counts': counts, 'summary': str(target), 'contactSheets': len(sheets)}, indent=2))


if __name__ == '__main__':
    main()
