#!/usr/bin/env python3
"""Require altered Java guards/updates to fail the actual Lean refinement.

Every semantic mutant first compiles its freshly extracted Generated module;
only failure of the independent Refinement module counts as a caught mutant.
All processes use the installed pinned Lean in network namespaces. Each run
writes new owned files and never modifies production/proof sources.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from lean_offline import installed_toolchain, isolated_command, clean_environment
import work_budget_bridge as bridge

FLAGS = ['--trust=0', '-DwarningAsError=true', '-DgenInjectivity=false', '-Dbackward.match.sparseCases=false', '-j1']
MUTANTS = (
    ('expiry-equality', 'elapsed >= state.limitNanos', 'elapsed > state.limitNanos'),
    ('exact-fuel', 'units > state.remaining', 'units >= state.remaining'),
    ('spending-direction', 'state.remaining -= units;', 'state.remaining += units;'),
    ('sampling-cadence', 'CLOCK_CHECK_CALLS = 1024;', 'CLOCK_CHECK_CALLS = 1025;'),
    ('initial-countdown', 'private int clockChecks = CLOCK_CHECK_CALLS;', 'private int clockChecks = 0;'),
    ('clock-subtraction', 'state.clock.getAsLong() - state.startedNanos',
     'state.clock.getAsLong() + state.startedNanos'),
)


def run_negative_controls(output, modules):
    output, modules = Path(output).resolve(), Path(modules).resolve()
    output.mkdir(parents=True, exist_ok=True)
    _, toolchain = installed_toolchain(ROOT)
    source = (ROOT / bridge.SOURCE).read_text()
    cases = []
    for name, old, new in MUTANTS:
        if source.count(old) != 1:
            raise ValueError('Negative control no longer targets one source expression: ' + name)
        directory = output / name
        directory.mkdir()
        for module in ('Semantics', 'Work'):
            shutil.copyfile(modules / (module + '.olean'), directory / (module + '.olean'))
        generated = bridge.generate(source.replace(old, new))
        (directory / 'Generated.lean').write_text(generated)
        shutil.copyfile(ROOT / 'formal/work_budget/Refinement.lean', directory / 'Refinement.lean')
        outcome = {}
        for module in ('Generated', 'Refinement'):
            command = isolated_command([str(toolchain / 'bin/lean'), *FLAGS,
                                        '-o', module + '.olean', module + '.lean'])
            completed = subprocess.run(command, cwd=directory, env=clean_environment(toolchain, directory),
                                       text=True, capture_output=True, timeout=60, check=False)
            log = completed.stdout + completed.stderr
            (directory / (module + '.log')).write_text(log)
            outcome[module] = completed.returncode
            if module == 'Generated' and completed.returncode != 0:
                raise RuntimeError('Semantic mutant did not reach the refinement proof: ' + name)
            if module == 'Refinement' and (completed.returncode == 0 or 'Refinement.lean:' not in log):
                raise RuntimeError('Semantic mutant was not rejected by the refinement proof: ' + name)
        cases.append({'name': name, 'generatedCompiled': outcome['Generated'] == 0,
                      'refinementRejected': outcome['Refinement'] != 0,
                      'generatedSha256': hashlib.sha256(generated.encode()).hexdigest()})
    result = {'status': 'PASS', 'semanticMutants': cases,
              'interpretation': 'Changed actual Java semantics compile, then fail the independent Lean refinement.'}
    with (output / 'result.json').open('x') as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
        stream.write('\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--output-root', type=Path, required=True)
    parser.add_argument('--modules', type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(run_negative_controls(args.output_root, args.modules), sort_keys=True))
        return 0
    except (OSError, subprocess.TimeoutExpired, RuntimeError) as error:
        print(json.dumps({'status': 'INFRASTRUCTURE_FAILURE', 'reason': str(error)}))
        return 2
    except (ValueError, bridge.BridgeRejected) as error:
        print(json.dumps({'status': 'REJECTED', 'reason': str(error)}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
