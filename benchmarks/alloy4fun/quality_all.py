#!/usr/bin/env python3
"""Publish the 181-invariant deterministic hint audit from immutable saved runs.

Streams one response archive at a time; no solver, network, or model calls.
The separate quality_collect.py collects current portal starter responses.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ARMS = {'canonical': ('live-full', 'live-canonical'),
        'ast': ('ast-full', 'live-ast'),
        'fm24-history': ('fm24-history', 'fm24-history'),
        'fm24-mutation': ('fm24-mutation', 'fm24-mutation'),
        'tar': ('tar', 'tar-depth-2')}
LABELS = {'canonical': 'Canonical', 'ast': 'AST', 'fm24-history': 'FM24 history',
          'fm24-mutation': 'FM24 + mutation', 'tar': 'TAR'}
PURPOSE = (' to specify ', ' to combine ', ' to find ', ' to perform ',
           ' to remove elements ', ' to get the ', ' to transpose ', ' to restrict ',
           ' to reverse ', ' to count ')


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def load(path):
    return json.loads(Path(path).read_text())


def public_native_hint(hint):
    """Conservatively hide all FM24 operand identities, including source ones."""
    if not hint:
        return hint
    hint = re.sub(r'((?:signature|variable) of type )\w+', r'\1[operand type hidden]', hint)
    hint = re.sub(r'"[^"\n]*"', '[operand hidden]', hint)
    return hint


def summary_stats():
    return {'counts': Counter(), 'statuses': Counter(), 'trace_length_histogram': Counter(),
            'kind_counts': Counter(), 'hint_sources': Counter()}


def native_hints(response):
    return [x for x in (response.get('native_result') or {}).get('native_trace', [])
            if isinstance(x.get('hint'), str) and x['hint'].strip()]


def measure(arm, response, available):
    """Return observable presentation features, never learner-success scores."""
    c = Counter()
    kinds = Counter()
    c['responses'] = 1
    c['hint_cases'] = int(available)
    if not available:
        return c, kinds, None
    if arm in ('canonical', 'ast'):
        ops = response.get('operations', [])
        atomic = [op for op in ops if not op.get('aggregate', False)]
        c['aggregate_units'] = sum(bool(op.get('aggregate')) for op in ops)
        c['atomic_units'] = len(atomic)
        c['raw_exact_units'] = sum(op.get('sourceLocation', {}).get('status') == 'located'
                                  and op.get('sourceLocation', {}).get('precision') == 'node' for op in atomic)
        c['raw_related_units'] = sum(op.get('sourceLocation', {}).get('status') == 'located'
                                    and op.get('sourceLocation', {}).get('precision') == 'related' for op in atomic)
        c['canonical_exact_units'] = sum(op.get('canonicalLocation', {}).get('status') == 'located'
                                        and op.get('canonicalLocation', {}).get('precision') == 'node' for op in atomic)
        c['replacement_operator_units'] = sum(bool(op.get('replacementOperator')) for op in atomic)
        c['any_raw_exact_cases'] = int(c['raw_exact_units'] > 0)
        c['all_raw_exact_cases'] = int(bool(atomic) and c['raw_exact_units'] == len(atomic))
        c['any_replacement_operator_cases'] = int(c['replacement_operator_units'] > 0)
        actions = [op.get('action') or op.get('description') or '' for op in atomic]
        c['repeated_action_units'] = len(actions) - len(set(actions))
        c['repeated_action_cases'] = int(c['repeated_action_units'] > 0)
        c['trace_matches_distance_cases'] = int(response.get('trace', {}).get('matchesDistance') is True)
        c['replay_verified_cases'] = int(response.get('trace', {}).get(
            'matrixReplayVerified' if arm == 'canonical' else 'astReplayVerified') is True)
        c['longer_than_10_units_cases'] = int(len(atomic) > 10)
        c['longer_than_20_units_cases'] = int(len(atomic) > 20)
        kinds.update(op.get('kind', 'missing') for op in atomic)
        return c, kinds, len(atomic)
    if arm == 'tar':
        hints = native_hints(response)
        c['native_hint_units'] = len(hints)
        c['raw_range_units'] = sum(all(isinstance(h.get(k), int) and h[k] > 0
            for k in ('line', 'column', 'end_line', 'end_column')) for h in hints)
        c['all_raw_range_cases'] = int(bool(hints) and c['raw_range_units'] == len(hints))
        c['bounded_checked_cases'] = int((response.get('independent_validation') or {}).get('verified_correct') is True)
        c['bounded_rejected_cases'] = int((response.get('independent_validation') or {}).get('verified_correct') is False)
        validation_status = (response.get('independent_validation') or {}).get('status')
        c['validation_error_cases'] = int(validation_status == 'validation_error')
        c['validation_timeout_cases'] = int(validation_status == 'validation_timeout')
        c['operator_purpose_phrase_cases'] = int(any(any(p in h['hint'] for p in PURPOSE) for h in hints))
        kinds.update(h.get('operation', 'missing') for h in hints)
        return c, kinds, len(hints)
    hint = response.get('hint') or response.get('native_hint') or ''
    c['native_hint_units'] = int(bool(hint))
    c['named_operator_or_quantifier_cases'] = int(bool(re.search(r"(?:operator|quantifier) \('[^']+'\)", hint)))
    c['operator_purpose_phrase_cases'] = int(any(p in hint for p in PURPOSE))
    c['relative_context_phrase_cases'] = int('within the ' in hint or 'inside of the ' in hint)
    c['operand_identity_redacted_cases'] = int(public_native_hint(hint) != hint)
    return c, kinds, int(bool(hint))


def add(stats, arm, response, row):
    c, kinds, length = measure(arm, response, row['hint_available'])
    stats['counts'].update(c)
    stats['statuses'][row['status']] += 1
    stats['kind_counts'].update(kinds)
    if length is not None:
        stats['trace_length_histogram'][str(length)] += 1
    if row.get('hint_source'):
        stats['hint_sources'][row['hint_source']] += 1


def safe_selected(arm, response, row, raw_hash, result_hash):
    record = {k: row.get(k) for k in ('status', 'hint_available', 'timed_out', 'fold')}
    record.update(result_record_sha256=result_hash, response_record_sha256=raw_hash)
    if arm in ('canonical', 'ast'):
        record.update(distance=response.get('distance'),
                      features=dict(measure(arm, response, row['hint_available'])[0]))
    elif arm == 'tar':
        record['native_hints'] = [{k: h[k] for k in
            ('hint', 'operation', 'line', 'column', 'end_line', 'end_column') if k in h}
            for h in native_hints(response)]
        record['bounded_verified'] = row.get('verified_correct')
    else:
        hint = response.get('hint') or response.get('native_hint')
        record.update(native_hint=public_native_hint(hint), publication_redacted=public_native_hint(hint) != hint,
                      hint_source=row.get('hint_source'))
    return record


def quantile(histogram, p):
    n = sum(histogram.values())
    if not n:
        return None
    cumulative = 0
    for value, count in sorted((int(k), v) for k, v in histogram.items()):
        cumulative += count
        if cumulative >= max(1, int((n - 1) * p) + 1):
            return value


def finalize(stats):
    out = {k: dict(v) for k, v in stats.items()}
    hist = stats['trace_length_histogram']
    n = sum(hist.values())
    out['hint_unit_distribution'] = {'count': n, 'median': quantile(hist, .5), 'p95': quantile(hist, .95),
        'mean': sum(int(k) * v for k, v in hist.items()) / n if n else None}
    return out


def percent(n, d):
    return f'{100*n/d:.2f}%' if d else '—'


def make_markdown(out):
    total = out['global']
    lines = ['# Hint availability and guidance across all 181 invariants', '',
        'This audit compares **Canonical, raw AST, FM24 historical, FM24 historical + mutation, and TAR** '
        'on every one of the 181 imported Alloy4Fun invariants. It separates **whether a hint exists** '
        'from **what guidance is actually present**. These are observed presentation properties, '
        'not learner-success scores.', '',
        'The complete [machine-readable evidence](benchmarks/alloy4fun-181-hint-quality.json) contains '
        'per-invariant corpus counts and guidance profiles, plus one matched starter per invariant '
        'with current public portal responses and saved native baseline hints. '
        'The [ten-exercise pilot](alloy4fun-hint-quality.md) supplies the earlier qualitative reviews '
        'and literal-edit probes; those ten reviews have **not** been extrapolated into 181 subjective ratings.', '',
        '## Scope and reproducibility', '',
        '- Corpus layer: all **42,388 incorrect submissions** in the completed 61,598-model study. '
        'The corrected frozen labels include 21,715 BOTH, 8,095 OVERCONSTRAINED and 12,578 UNDERCONSTRAINED inputs. '
        'All five saved result and native-response archives were streamed, hash checked against the '
        'published evidence, and joined by case identity. The original results are unchanged.',
        '- Current-portal layer: **181 existing catalogue starters**, selected without looking at outputs '
        '(175 UNDERCONSTRAINED and 6 OVERCONSTRAINED), with one fresh Canonical and AST request each. '
        'The portal uses its full compatible CORRECT pool, including its oracle; archived Canonical/AST '
        'and FM24 use their recorded five-fold held-out pools. The archived Canonical/AST starter controls '
        'remain in the JSON. This is not an equal-training-data comparison.',
        '- TAR was not rerun: depth two, recorded 60-second search budget, and an independent bounded '
        'Alloy validation attempt for emitted candidate repairs. Successful, rejected and unresolved '
        'checks are distinguished below. A bounded check is not universal equivalence.',
        '- No paid Luna explanations, new AI quality ratings, learner study, or all-corpus literal-edit '
        'success experiment was performed. Current request wall times are diagnostics, not a new runtime ranking.',
        '- Public evidence omits oracle bodies, hidden target expressions and complete candidate repairs. '
        'FM24 operand type/name phrases are conservatively redacted, including source-side identities; '
        'operator names and explanatory wording remain. This records a disclosure difference, not an error in FM24.', '',
        'The [frozen 181-input selection](../benchmarks/alloy4fun/protocol/quality-selection-181.json) '
        'is included in the repository, containing only learner drafts, public questions and source identities. '
        'The [reproducer](../benchmarks/alloy4fun/quality_all.py) reads one archive at a time and retains '
        'only its compact result index, per-invariant counters and the 181 selected responses. It never '
        'starts solvers or connects to a service. The fresh collector is single-worker; scratch data '
        'lives in `build/benchmarks/hint-quality-181/`, not the system temporary directory. '
        'To collect new current-portal evidence, first build the engine using the repository setup guide, '
        'then run the following from the repository root. A fresh study directory is required; '
        'existing evidence is never overwritten.', '',
        '```bash',
        'python3 -c "from pathlib import Path; import shutil; p=Path(\'build/benchmarks/hint-quality-181-reproduction\'); p.mkdir(parents=True, exist_ok=False); shutil.copyfile(\'benchmarks/alloy4fun/protocol/quality-selection-181.json\', p/\'selection.json\')"',
        'python3 benchmarks/alloy4fun/quality_collect.py \\',
        '  --selection build/benchmarks/hint-quality-181-reproduction/selection.json \\',
        '  --output build/benchmarks/hint-quality-181-reproduction/current.json',
        'python3 benchmarks/alloy4fun/quality_all.py \\',
        '  --study build/benchmarks/hint-quality-181-reproduction \\',
        '  --output build/benchmarks/hint-quality-181-reproduction/evidence.json \\',
        '  --markdown build/benchmarks/hint-quality-181-reproduction/report.md',
        '```', '',
        'The first two commands use the checked-in selection and local production portal. The final '
        'archive audit additionally requires the preserved research run directory '
        '`build/benchmarks/alloy4fun-v2` (or `--data /path/to/run`). Those large private native-response '
        'archives are not in a public clone; the published JSON and per-record/archive hashes are. '
        'A rerun uses the currently installed runtime, so record its new provenance rather than assuming '
        'it reproduces historical hints byte for byte.', '', '## Corpus-wide availability', '',
        'A hit means the saved run reports a nonempty native hint on an incorrect submission. '
        'It does not mean a completed repair, a syntactically valid first edit, or educational benefit. '
        'All eligible incorrect cases remain in each denominator, including failures and timeouts.', '',
        '| Method | Hints / incorrect inputs | Availability | Invariants with ≥1 hint | Macro availability |',
        '| --- | ---: | ---: | ---: | ---: |']
    for arm in ARMS:
        c = total[arm]['counts']; profiles = [x['corpus'][arm]['counts'] for x in out['invariants']]
        macro = sum(x['hint_cases']/x['responses'] for x in profiles)/len(profiles)
        lines.append(f"| {LABELS[arm]} | {c['hint_cases']:,} / {c['responses']:,} | {percent(c['hint_cases'], c['responses'])} | {sum(x['hint_cases']>0 for x in profiles)} / 181 | {100*macro:.2f}% |")
    lines += ['', 'Macro availability weights each invariant equally; overall availability weights each submission equally. '
        'FM24 history and mutation-enabled FM24 are separate configurations and must not be merged into one result.', '',
        '## Guidance properties observed across the whole incorrect corpus', '',
        'For Canonical/AST, an **exact raw location** means metadata identifies the selected learner node; '
        'it is not independent evidence that the node is the learner’s conceptual defect. An insertion may '
        'use a shared related anchor. A **named replacement operator** communicates an operator while '
        'retaining hidden operands. A repeated action is a second occurrence of exactly the same action '
        'text within a response; different affected nodes can legitimately require the same action. '
        'Trace length measures reading burden only: the methods use different edit units and targets.', '',
        '| Property | Canonical | AST |', '| --- | ---: | ---: |']
    def pair(label, key, den=None):
        values=[]
        for a in ('canonical','ast'):
            c=total[a]['counts']; v=c.get(key,0)
            values.append(f'{v:,}' + (f" / {c[den]:,} ({percent(v,c[den])})" if den else ''))
        lines.append('| '+label+' | '+' | '.join(values)+' |')
    pair('Atomic operations', 'atomic_units')
    pair('Exact raw-node operations', 'raw_exact_units', 'atomic_units')
    pair('Related raw-context operations', 'raw_related_units', 'atomic_units')
    pair('Exact canonical-node operations', 'canonical_exact_units', 'atomic_units')
    pair('Named replacement-operator operations', 'replacement_operator_units', 'atomic_units')
    pair('Hints whose every atomic operation has an exact raw location', 'all_raw_exact_cases', 'hint_cases')
    pair('Hints containing repeated action wording', 'repeated_action_cases', 'hint_cases')
    pair('Hints longer than 20 atomic operations', 'longer_than_20_units_cases', 'hint_cases')
    pair('Trace-cost metadata matches distance', 'trace_matches_distance_cases', 'hint_cases')
    for label,key in [('Median operations per hint','median'),('95th percentile operations per hint','p95')]:
        lines.append('| '+label+' | '+' | '.join(str(total[a]['hint_unit_distribution'][key]) for a in ('canonical','ast'))+' |')
    lines += ['', 'Canonical locations are intentionally unavailable in AST mode. Canonical also emitted '
        f"{total['canonical']['counts']['aggregate_units']:,} aggregate notices, excluded from the atomic-unit percentages and lengths; "
        'these notices account for remaining edit cost without pretending to be an individual located edit. '
        'Replay/count checks concern '
        'the internal structural trace; **they do not prove that taking one displayed instruction literally '
        'will compile or improve behavior**. The earlier pilot constructed a type-error counterexample '
        'for a literal Canonical operator replacement. That limitation remains applicable; this audit '
        'does not reinterpret a high location or trace-consistency rate as repair correctness.', '',
        '| Native baseline guidance property | FM24 history | FM24 + mutation | TAR |',
        '| --- | ---: | ---: | ---: |']
    for label,key in [('Hints with an operator-purpose phrase','operator_purpose_phrase_cases')]:
        lines.append('| '+label+' | '+' | '.join(f"{total[a]['counts'].get(key,0):,} / {total[a]['counts']['hint_cases']:,}" for a in ('fm24-history','fm24-mutation','tar'))+' |')
    for label,key in [('Hints naming an operator/quantifier','named_operator_or_quantifier_cases'),
                      ('Hints with a relative-context phrase','relative_context_phrase_cases'),
                      ('Hints requiring operand-identity redaction','operand_identity_redacted_cases')]:
        lines.append('| '+label+' | '+' | '.join(f"{total[a]['counts'].get(key,0):,} / {total[a]['counts']['hint_cases']:,}" for a in ('fm24-history','fm24-mutation'))+' | Not assessed by this FM24-specific detector |')
    tc=total['tar']['counts']
    lines += [f"| TAR hints with all native line/column ranges | — | — | {tc.get('all_raw_range_cases',0):,} / {tc['hint_cases']:,} |",
        f"| TAR hints with a bounded-validated complete repair behind them | — | — | {tc.get('bounded_checked_cases',0):,} / {tc['hint_cases']:,} |", '',
        f"TAR has **{tc['bounded_rejected_cases']} independently rejected candidates**, "
        f"**{tc['validation_error_cases']} validation errors** and **{tc['validation_timeout_cases']} validation timeouts** "
        'among native-hint cases. These remain native-hint availability hits; they are not counted as '
        'validated repairs. An unknown validation result is not a constructed counterexample.', '',
        'The phrase detector is intentionally mechanical: it tests the fixed FM24 templates for '
        'operator/quantifier names, phrases such as “to specify” or “to combine”, and “within the” '
        'or “inside of the”. It does not judge whether an explanation is relevant, correct, understandable '
        'or sufficient. TAR’s absence of those phrases reflects its terse mutation cues, not a failed repair. '
        'TAR line/column ranges were counted as supplied; these are not portal body-offset highlights. '
        'FM24’s relative context is also different from an exact editor address.', '',
        '### Assessment', '',
        '**Canonical and AST have the strongest availability in this corpus** and provide inspectable '
        'multi-step structure with source references. Their long and repetitive traces, hidden operands '
        'and normalization/tree-edit semantics leave interpretation to the learner. AST avoids canonical '
        'normalization, but its delete/insert operations can also differ from ordinary text edits. '
        'Neither trace length nor distance establishes a quality ordering between these two modes.', '',
        '**FM24 history explains operator purpose more directly when a historical hint is available.** '
        'The mutation-enabled arm extends availability, but its extra mutation cues use terser wording. '
        'Some native hints disclose operand identities; the public comparison marks those omissions '
        'rather than awarding the portal an unfair specificity disadvantage under its stricter disclosure policy.', '',
        '**TAR provides terse local mutation guidance, with a positively validated bounded repair '
        f"behind {tc['bounded_checked_cases']:,} of its {tc['hint_cases']:,} native-hint cases.** "
        'That is a stronger repair-evidence property than structural distance for that verified subset, '
        'while its missing hints and broad wording limit the guidance available to an individual learner. '
        'The experiment does not show that displaying or following its hint alone reproduces its hidden repair.', '',
        'A defensible product priority is to keep both portal views and their exact occurrence highlighting, '
        'then improve first-step pedagogy and compilation safety without exposing target expressions. '
        'An actual novice study and first-edit behavioral validation remain open; no overall educational '
        'winner is claimed.', '', '## Current portal: one existing starter for every invariant', '',
        'This layer tests the current public interface. All 181 inputs are incorrect under the corrected '
        'corpus labels, but they are strongly biased toward overcoverage (the corpus UNDERCONSTRAINED label). '
        'Four existing starters are whitespace-only: `cv_v1-inv1`, `cv_v1-inv3`, `cv_v1-inv4` and '
        '`productionLine_v1-inv1`. The current HTTP interface rejects their empty bodies before the '
        'hint engine, while the saved benchmark passes complete Alloy modules, where an empty predicate '
        'body is syntactically meaningful. Both current modes return hints for **all 177 nonempty starters**; '
        'the four validation outcomes are preserved without substitution. They do not replace the '
        'all-incorrect corpus denominator above.', '',
        '| Method | Current/matched starter hints |', '| --- | ---: |']
    for a in ARMS:
        v=out['starter_summary'][a]
        lines.append(f"| {LABELS[a]} | {v['hint_cases']} / 181 |")
    lines += ['', 'The public JSON preserves **all** current Canonical/AST operations and native saved '
        'baseline hints for these same inputs, not only successful cases. The archived live-mode controls '
        'allow a reader to distinguish current full-pool behavior from the earlier benchmark configuration. '
        'No absent native hint is assigned a subjective guidance score.', '',
        '## Per-invariant availability and guidance', '',
        'Every row uses all incorrect submissions of that invariant. **C/A** means Canonical/AST. '
        'The raw-location column is the percentage of atomic operations carrying exact raw-node metadata; '
        'the length column is median atomic operations per available hint. FM24 columns count available '
        'native hints, with “H/+M” denoting history/mutation-enabled. Guidance details, statuses, '
        'operator specificity, repetition and the matched starter outputs are in the linked JSON. '
        'FM24 purpose counts apply equally to both variants because all extra mutation hints lack these '
        'particular purpose phrases. TAR checked counts show the verified subset of the adjacent hint count.', '',
        '| Invariant | Incorrect N | Canonical hints | AST hints | FM24 H / +M hints | TAR hints / checked | C/A raw exact % | C/A median steps | FM24 purpose hints |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for row in out['invariants']:
        p=row['corpus']; c=p['canonical']['counts']; a=p['ast']['counts']
        lines.append(f"| `{row['exercise_id']}` | {c['responses']} | {c['hint_cases']} | {a['hint_cases']} | {p['fm24-history']['counts']['hint_cases']} / {p['fm24-mutation']['counts']['hint_cases']} | {p['tar']['counts']['hint_cases']} / {p['tar']['counts'].get('bounded_checked_cases',0)} | {percent(c['raw_exact_units'],c['atomic_units'])} / {percent(a['raw_exact_units'],a['atomic_units'])} | {p['canonical']['hint_unit_distribution']['median']} / {p['ast']['hint_unit_distribution']['median']} | {p['fm24-history']['counts'].get('operator_purpose_phrase_cases',0)} |")
    lines += ['', '## Evidence limits and integrity', '',
        'This is a complete 181-invariant **availability and guidance-feature audit**, not 181 independent '
        'human quality evaluations. Every archive contains 61,598 distinct expected cases; all incorrect '
        'subsets and per-invariant hint counts are checked against the published final report. '
        'TAR response/result hash bindings are also checked. The output records archive, source-manifest, '
        'selection, current-response, collector/runtime and analyzer SHA-256 identities. '
        'Original run artifacts, the ten-case pilot, and their provenance remain unchanged.', '',
        'See the [full comparison](alloy4fun-comparison.md) for runtime, resource configuration, '
        'fold policy, limitations, the two corrected legacy labels and the corrected-engine rerun history. '
        'Correct controls are intentionally excluded from the availability denominator here; a nonempty '
        'hint on a held-out CORRECT predicate is not evidence that the original full correct-pool policy failed.', '']
    return '\n'.join(lines)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--data',type=Path,default=ROOT/'build/benchmarks/alloy4fun-v2')
    p.add_argument('--study',type=Path,default=ROOT/'build/benchmarks/hint-quality-181')
    p.add_argument('--output',type=Path,default=ROOT/'docs/benchmarks/alloy4fun-181-hint-quality.json')
    p.add_argument('--markdown',type=Path,default=ROOT/'docs/alloy4fun-181-hint-quality.md')
    args=p.parse_args()
    if args.output.exists() or args.markdown.exists():
        p.error('Use fresh output paths; existing evidence is preserved.')
    report_path=ROOT/'docs/benchmarks/alloy4fun-results.json'
    report=load(report_path); provenance=report['provenance']
    audit_path=args.data/'evidence-audit.json'
    assert sha(audit_path)==provenance['raw_response_audit']['sha256']
    audit={x['tool']:x for x in load(audit_path)['tools']}
    selection_path=args.study/'selection.json'; current_path=args.study/'current.json'
    selection=load(selection_path); current=load(current_path)
    assert current['selection_sha256']==sha(selection_path)
    selected={c['case_id']:c for c in selection['cases']}
    current_cases={c['case_id']:c for c in current['cases']}
    assert len(selected)==181 and set(current_cases)==set(selected)
    exids={c['exercise_id'] for c in selected.values()}; assert len(exids)==181
    expected={}
    with (args.data/'cases.jsonl').open() as stream:
        for line in stream:
            c=json.loads(line)
            assert c['case_id'] not in expected
            expected[c['case_id']]=(f"{c['group']}-{c['predicate']}",c['cohort_status'],c['source_sha256'])
    assert len(expected)==61598
    assert sum(c[1]!='CORRECT' for c in expected.values())==42388
    assert {c[0] for c in expected.values()}==exids
    for cid,case in selected.items():
        assert expected[cid]==(case['exercise_id'],case['source_classification'],case['source_sha256'])
    corpus={e:{a:summary_stats() for a in ARMS} for e in exids}
    overall={a:summary_stats() for a in ARMS}; starters={cid:{} for cid in selected}; archives={}
    for arm,(folder,registered) in ARMS.items():
        rp=args.data/folder/'results.jsonl'; hp=args.data/folder/'responses.jsonl.gz'
        rh=sha(rp); hh=sha(hp)
        assert rh==provenance['result_snapshots'][registered]['sha256']
        assert hh==audit[registered]['sha256']['responses.jsonl.gz']
        archives[arm]={'results_sha256':rh,'responses_sha256':hh}
        rows={}
        with rp.open('rb') as stream:
            for raw in stream:
                r=json.loads(raw); cid=r['case_id']; assert cid not in rows
                ex,cl,source=expected[cid]
                assert r['source_sha256']==source and r['cohort_status']==cl
                assert f"{r['group']}-{r['predicate']}"==ex
                rows[cid]=({k:r.get(k) for k in ('status','hint_available','timed_out','fold','hint_source','verified_correct')},
                           hashlib.sha256(raw).hexdigest(),hashlib.sha256(json.dumps(r,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest())
        assert set(rows)==set(expected)
        seen=set()
        with gzip.open(hp,'rb') as stream:
            for raw in stream:
                wrapper=json.loads(raw); cid=wrapper['case_id']; assert cid not in seen; seen.add(cid)
                row,rowhash,binding=rows[cid]; ex,cl,source=expected[cid]
                if arm=='tar':
                    assert wrapper['source_sha256']==source and wrapper['result_sha256']==binding
                response=wrapper.get('response') or {}
                if cl!='CORRECT':
                    add(corpus[ex][arm],arm,response,row); add(overall[arm],arm,response,row)
                if cid in selected:
                    starters[cid][arm]=safe_selected(arm,response,row,hashlib.sha256(raw).hexdigest(),rowhash)
        assert seen==set(expected)
        published=report['metrics']['tools'][registered]['incorrect']
        assert overall[arm]['counts']['hint_cases']==published['hints']
        for pr in published['by_exercise']:
            stats=corpus[f"{pr['group']}-{pr['predicate']}"][arm]['counts']
            assert stats['responses']==pr['cases'] and stats['hint_cases']==pr['hints']
        print(arm,dict(overall[arm]['counts']),flush=True)
        del rows
    invariants=[]; starter_summary={a:{'hint_cases':0,'statuses':Counter()} for a in ARMS}
    for case in selection['cases']:
        cid=case['case_id']; fresh=current_cases[cid]['responses']
        row={**case,'corpus':{a:finalize(corpus[case['exercise_id']][a]) for a in ARMS},
             'current':{},'saved_starter':starters[cid]}
        for a in ('canonical','ast'):
            r=fresh[a]['response']; assert r['exerciseId']==case['exercise_id']
            if r['status']=='ok':
                assert r['distance']==sum(op['cost'] for op in r.get('operations',[]))
            for op in r.get('operations',[]):
                for span in op.get('sourceLocation',{}).get('ranges',[]):
                    assert op['sourceLocation']['coordinateSystem']=='body'
                    raw=case['learner_body'].encode('utf-16-le')
                    assert raw[span['start']*2:span['end']*2].decode('utf-16-le')==span['text']
            row['current'][a]=r
            starter_summary[a]['hint_cases']+=int(bool(r.get('operations')))
            starter_summary[a]['statuses'][r['status']]+=1
        for a in ('fm24-history','fm24-mutation','tar'):
            starter_summary[a]['hint_cases']+=int(starters[cid][a]['hint_available'])
            starter_summary[a]['statuses'][starters[cid][a]['status']]+=1
        invariants.append(row)
    for a,stats in starter_summary.items():
        assert 0 <= stats['hint_cases'] <= 181 and sum(stats['statuses'].values())==181
    out={'schema_version':1,'generated_at_utc':datetime.now(timezone.utc).isoformat(),
         'scope':{'invariants':181,'corpus_cases':61598,'incorrect_cases':42388,'fresh_requests':362,
             'measures':'Observed guidance features, not learner outcomes or first-edit correctness.',
             'deterministic_only':True,'luna_calls':0,'new_subjective_quality_ratings':False,
             'operand_redaction':'FM24 operand types and double-quoted names hidden conservatively; operators preserved.'},
         'provenance':{'final_report_sha256':sha(report_path),'response_audit_sha256':sha(audit_path),
             'cases_sha256':sha(args.data/'cases.jsonl'),'selection_sha256':sha(selection_path),
             'current_responses_sha256':sha(current_path),'analyzer_sha256':sha(Path(__file__)),
             'collector_sha256':sha(ROOT/'benchmarks/alloy4fun/quality_collect.py'),
             'archive_sha256':archives,'current_runtime_sha256':current['runtime_sha256'],
             'current_collected_at_utc':current['collected_at_utc']},
         'detectors':{'operator_purpose_phrases':list(PURPOSE),
             'repeated_action':'Within each response, atomic count minus number of distinct action strings.',
             'locations':'Only supplied status=located and precision=node; not independently adjudicated.',
             'quantiles':'Histogram order statistic floor((n-1)*p), zero-based.'},
         'global':{a:finalize(s) for a,s in overall.items()},
         'starter_summary':{a:dict(s) for a,s in starter_summary.items()},'invariants':invariants}
    args.output.write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n')
    args.markdown.write_text(make_markdown(out))
    print(json.dumps({'status':'PASS','invariants':len(invariants),'starter_summary':out['starter_summary'],
                      'public_evidence_sha256':sha(args.output)},indent=2))


if __name__=='__main__':
    main()
