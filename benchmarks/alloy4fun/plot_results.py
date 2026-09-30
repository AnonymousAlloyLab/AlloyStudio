#!/usr/bin/env python3
"""Export standard benchmark figures only from a complete, corrected v3 report.

Requires matplotlib. PNG and SVG are written together with an input/output hash
manifest. This script refuses RUNNING/partial reports before creating figures.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.summarize import (
    METHOD_VERSION, EXPECTED_TOTAL, EXPECTED_INCORRECT, CORRECTION_REGISTRY,
    CORRECTION_CASE_IDS, SOURCE_CLASSIFICATION_COUNTS, CORRECTED_CLASSIFICATION_COUNTS,
    RECOVERY_LIMITS, RECOVERY_WORKERS, TOOL_DIRECTORIES, SHA256,
)
TOOLS = ('live-canonical', 'live-ast', 'tar-depth-2', 'fm24-history', 'fm24-mutation')
LABELS = ('Alloy Studio canonical', 'Alloy Studio raw AST', 'TAR (depth 2)',
          'FM24 historical', 'FM24 historical + mutation')
BAR_LABELS = ('Alloy Studio\ncanonical', 'Alloy Studio\nraw AST', 'TAR\ndepth 2',
              'FM24\nhistorical', 'FM24 historical\n+ mutation')
COLORS = ('#0072B2', '#D55E00', '#009E73', '#CC79A7', '#6B5B1E')
MARKERS = ('o', 's', '^', 'D', 'v')
THRESHOLDS = (.1, .25, 1., 2., 5., 10., 30., 60.)
CAVEAT = ('Sequential method arms with 16 concurrent requests per arm; '
          'wall times include contention within each arm, not isolated interactive latency. Luna is excluded.')
RECOVERY_CAVEAT_LINES = (
    'Sequential arms: Alloy Studio/FM24, 16 workers; TAR, 4 workers, 6 GiB cap, 4-CPU quota after host OOM.',
    'Unequal resources: descriptive timings and 60s availability, not a speed ranking or isolated latency. Luna excluded.',
)


def execution_caveat(summary):
    if summary.get('provenance', {}).get('execution_profile', {}).get('kind') == 'oom_recovery':
        return RECOVERY_CAVEAT_LINES
    first, second = CAVEAT.split('; ', 1)
    return (first + ';', second)


def validate_execution_evidence(summary):
    evidence = summary.get('provenance', {}).get('execution_profile', {})
    if evidence.get('status') != 'MATCHED' or not SHA256.fullmatch(evidence.get('resource_profile_sha256', '')):
        raise ValueError('Expected hash-bound completed execution profile')
    kind = evidence.get('kind')
    if kind == 'oom_recovery':
        workers = {tool: RECOVERY_WORKERS[arm] for tool, arm in TOOL_DIRECTORIES.items()}
        if (evidence.get('timing_comparison') != 'descriptive_unequal_resource_configurations'
                or any(evidence.get('effective_limits', {}).get(key) != value for key, value in RECOVERY_LIMITS.items())
                or evidence.get('fresh_tar_resumed_rows') != 0
                or evidence.get('preserved_arms') != sorted(set(TOOL_DIRECTORIES.values()) - {'tar'})):
            raise ValueError('Incomplete or inconsistent OOM recovery execution evidence')
        for name in ('guard_report_sha256', 'guard_source_sha256'):
            if not SHA256.fullmatch(evidence.get(name, '')):
                raise ValueError('Missing OOM recovery evidence hash: ' + name)
        if summary['runs']['tar-depth-2'].get('resumed_rows') != 0:
            raise ValueError('TAR recovery figures cannot combine old and new rows')
    elif kind == 'sequential_equal_workers':
        workers = {tool: 16 for tool in TOOLS}
    else:
        raise ValueError('Unregistered execution profile for figures')
    if evidence.get('workers_by_tool') != workers:
        raise ValueError('Execution profile worker inventory mismatch')
    for tool, count in workers.items():
        if type(summary['runs'][tool].get('workers')) is not int or summary['runs'][tool]['workers'] != count:
            raise ValueError(f'{tool}: workers disagree with bound execution profile')


def _integer(value, label):
    if type(value) is not int or value < 0:
        raise ValueError(f'{label} must be a nonnegative integer')
    return value


def _rate(value, expected, label):
    if type(value) not in (int, float) or not math.isfinite(value) or not math.isclose(value, expected, rel_tol=1e-12, abs_tol=1e-12):
        raise ValueError(f'{label} disagrees with measured counts')


def validate_summary(summary, *, expected_total=EXPECTED_TOTAL, expected_incorrect=EXPECTED_INCORRECT):
    if summary.get('schema_version') != 2 or summary.get('method_version') != METHOD_VERSION:
        raise ValueError('Expected an alloy4fun-comparison-v3 completed report with corrected labels')
    if summary.get('status') != 'COMPLETE' or summary.get('incomplete_tools') != []:
        raise ValueError('Figures require COMPLETE results; RUNNING or partial reports are refused')
    metrics = summary['metrics']
    cohort = metrics['cohort']
    if (cohort.get('cases'), cohort.get('incorrect'), cohort.get('correct'), cohort.get('source')) != (
            expected_total, expected_incorrect, expected_total - expected_incorrect, 'expected_manifest'):
        raise ValueError('Report cohort/denominator does not match the requested experiment')
    if metrics.get('timeout_seconds') != 60:
        raise ValueError('Expected the shared 60-second budget')
    if set(summary['runs']) != set(TOOLS) or set(metrics['tools']) != set(TOOLS):
        raise ValueError('Figures require canonical, raw AST, TAR and both FM24 arms')
    audit = summary.get('provenance', {}).get('actual_source_audit', {})
    if audit.get('status') != 'MATCHED' or audit.get('source_files_checked') != expected_total:
        raise ValueError('Expected a completed actual-source hash audit')
    raw_audit = summary.get('provenance', {}).get('raw_response_audit', {})
    if raw_audit.get('status') != 'PASS' or raw_audit.get('scope') != 'raw-response-correspondence' or raw_audit.get('tools') != len(TOOLS):
        raise ValueError('Expected passed raw-response correspondence for all five tools')
    labels = summary.get('provenance', {}).get('label_corrections', {})
    if labels.get('status') != 'MATCHED' or labels.get('registry_path') != CORRECTION_REGISTRY:
        raise ValueError('Expected a matched label-correction registry')
    production = (expected_total, expected_incorrect) == (EXPECTED_TOTAL, EXPECTED_INCORRECT)
    if production:
        if (labels.get('applied_count') != 2 or labels.get('case_ids') != sorted(CORRECTION_CASE_IDS)
                or labels.get('source_classification_counts') != SOURCE_CLASSIFICATION_COUNTS
                or labels.get('effective_classification_counts') != CORRECTED_CLASSIFICATION_COUNTS):
            raise ValueError('Corrected v3 label-correction inventory/denominators mismatch')
        if labels.get('registry_sha256') != hashlib.sha256((ROOT / CORRECTION_REGISTRY).read_bytes()).hexdigest():
            raise ValueError('Report label-correction registry hash is stale')
        validate_execution_evidence(summary)
    curves, hints = [], []
    for tool in TOOLS:
        run, result = summary['runs'][tool], metrics['tools'][tool]
        if run.get('state') != 'COMPLETE' or run.get('observed') != expected_total or run.get('expected') != expected_total or result.get('complete') is not True:
            raise ValueError(f'{tool}: incomplete run')
        wrong = result['incorrect']
        if wrong.get('cases') != expected_incorrect or wrong.get('observed') != expected_incorrect or wrong.get('missing') != 0:
            raise ValueError(f'{tool}: missing incorrect-input observations')
        count = _integer(wrong['hints'], tool + ' hint count')
        if count > expected_incorrect:
            raise ValueError(f'{tool}: hints exceed denominator')
        _rate(wrong['hit_rate_micro'], count / expected_incorrect, tool + ' Hit Rate')
        timing = wrong['hints_within_seconds']
        if set(timing) != {str(t) for t in THRESHOLDS}:
            raise ValueError(f'{tool}: missing or changed timing thresholds')
        points, previous = [], 0
        for threshold in THRESHOLDS:
            point = timing[str(threshold)]
            observed = _integer(point['hints'], tool + ' timely hint count')
            if not previous <= observed <= count:
                raise ValueError(f'{tool}: timely counts are decreasing or exceed total hints')
            _rate(point['rate'], observed / expected_incorrect, tool + ' timely rate')
            points.append(100 * observed / expected_incorrect)
            previous = observed
        if previous != count:
            raise ValueError(f'{tool}: 60-second availability differs from headline Hit Rate')
        curves.append(points)
        hints.append(count)
    return curves, hints


def hit_rate_label(count, denominator):
    """Keep one miss in the full corpus visibly distinct from 100% availability."""
    return f'{100 * count / denominator:.4f}%\n{count:,}/{denominator:,}'


def generate_figures(summary, output_dir, *, input_sha256, expected_total=EXPECTED_TOTAL, expected_incorrect=EXPECTED_INCORRECT):
    curves, hints = validate_summary(summary, expected_total=expected_total, expected_incorrect=expected_incorrect)
    caveat_lines = execution_caveat(summary)
    caveat = ' '.join(caveat_lines)
    recovery = summary.get('provenance', {}).get('execution_profile', {}).get('kind') == 'oom_recovery'
    labels = tuple(label + f" ({summary['runs'][tool]['workers']} workers)" if recovery else label for tool, label in zip(TOOLS, LABELS))
    bar_labels = tuple(label + f"\n{summary['runs'][tool]['workers']} workers" if recovery else label for tool, label in zip(TOOLS, BAR_LABELS))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.ticker import PercentFormatter, ScalarFormatter

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    metadata = {'Title': 'Alloy4Fun native hint benchmark',
                'Description': f'Five methods on {expected_incorrect:,} incorrect inputs. {caveat}',
                'Creator': 'Alloy Studio benchmark plot_results.py', 'Date': summary.get('generated_at')}
    exported = []
    settings = {'font.family': 'DejaVu Sans', 'font.size': 11, 'svg.fonttype': 'none',
                'svg.hashsalt': input_sha256, 'axes.spines.top': False, 'axes.spines.right': False,
                'axes.titleweight': 'bold', 'figure.facecolor': 'white', 'savefig.facecolor': 'white'}
    with tempfile.TemporaryDirectory(prefix='.figures-', dir=output_dir) as temporary, plt.rc_context(settings):
        stage = Path(temporary)
        fig, ax = plt.subplots(figsize=(11.5, 7.2))
        for label, color, marker, values in zip(labels, COLORS, MARKERS, curves):
            ax.plot(THRESHOLDS, values, label=label, color=color, marker=marker, markersize=6,
                    linewidth=2, markeredgecolor='white', markeredgewidth=.5)
        ax.set_xscale('log')
        ax.set_xticks(THRESHOLDS)
        ax.xaxis.set_major_formatter(ScalarFormatter())
        ax.set_xlim(.09, 70)
        ax.set_ylim(0, 105)
        ax.yaxis.set_major_formatter(PercentFormatter(100))
        ax.set_xlabel('Request wall-time threshold (seconds; logarithmic axis)')
        ax.set_ylabel(f'Inputs receiving a native hint (% of {expected_incorrect:,} incorrect inputs)')
        ax.set_title('Timely native-hint availability', loc='left', pad=18)
        ax.grid(axis='y', alpha=.25)
        ax.legend(loc='lower right', frameon=True, framealpha=.95, fontsize=10)
        fig.text(.12, .09, 'Points are measured at the stated thresholds; connecting lines are visual guides.', fontsize=9)
        fig.text(.12, .045, caveat_lines[0], fontsize=9)
        fig.text(.12, .02, caveat_lines[1], fontsize=9)
        fig.subplots_adjust(left=.12, right=.98, top=.89, bottom=.2)
        figures = [(fig, 'alloy4fun-timely-hints')]

        fig2, ax2 = plt.subplots(figsize=(11.5, 7.2))
        values = [100 * count / expected_incorrect for count in hints]
        bars = ax2.bar(range(5), values, color=COLORS, edgecolor='#333333', linewidth=.5, width=.67)
        for bar, count, value in zip(bars, hints, values):
            ax2.text(bar.get_x() + bar.get_width() / 2, value + 1.2,
                     hit_rate_label(count, expected_incorrect), ha='center', va='bottom', fontsize=10)
        ax2.set_xticks(range(5), bar_labels)
        ax2.set_ylim(0, 113)
        ax2.yaxis.set_major_formatter(PercentFormatter(100))
        ax2.set_yticks(range(0, 101, 20))
        ax2.set_ylabel(f'Native hint Hit Rate (% of {expected_incorrect:,} incorrect inputs)')
        ax2.set_title('Native hint availability within 60 seconds', loc='left', pad=18)
        ax2.grid(axis='y', alpha=.25)
        ax2.set_axisbelow(True)
        fig2.text(.10, .105, 'A returned hint does not establish a correct repair, behavioral improvement, or educational effectiveness.', fontsize=9)
        fig2.text(.10, .065, caveat_lines[0], fontsize=9)
        fig2.text(.10, .04, caveat_lines[1], fontsize=9)
        fig2.subplots_adjust(left=.1, right=.98, top=.88, bottom=.25)
        figures.append((fig2, 'alloy4fun-hit-rates'))
        try:
            for figure, basename in figures:
                figure.savefig(stage / (basename + '.svg'), metadata=metadata)
                figure.savefig(stage / (basename + '.png'), dpi=180,
                               metadata={'Title': metadata['Title'], 'Description': metadata['Description'],
                                         'SourceDigest': input_sha256})
                exported.extend([basename + '.svg', basename + '.png'])
            record = {'schema_version': 1, 'report_schema_version': 2, 'input_sha256': input_sha256,
                      'report_method_version': METHOD_VERSION,
                      'label_corrections': summary['provenance']['label_corrections'],
                      'matplotlib_version': matplotlib.__version__, 'incorrect_denominator': expected_incorrect,
                      'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      'timing_caveat': caveat, 'tools': list(TOOLS),
                      'execution_profile': summary.get('provenance', {}).get('execution_profile'),
                      'files': {name: hashlib.sha256((stage / name).read_bytes()).hexdigest() for name in exported}}
            (stage / 'alloy4fun-figures.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
            exported.append('alloy4fun-figures.json')
            for name in exported:
                os.replace(stage / name, output_dir / name)
        finally:
            for figure, _ in figures:
                plt.close(figure)
    return [output_dir / name for name in exported]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT / 'docs/benchmarks/alloy4fun-results.json')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'docs/benchmarks')
    args = parser.parse_args()
    try:
        data = args.input.read_bytes()
        summary = json.loads(data)
        written = generate_figures(summary, args.output_dir, input_sha256=hashlib.sha256(data).hexdigest())
    except (ValueError, KeyError, TypeError, OSError, ImportError) as error:
        print('Benchmark figures refused: ' + str(error), file=__import__('sys').stderr)
        return 1
    print(json.dumps({'status': 'COMPLETE', 'written': [str(path) for path in written]}, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
