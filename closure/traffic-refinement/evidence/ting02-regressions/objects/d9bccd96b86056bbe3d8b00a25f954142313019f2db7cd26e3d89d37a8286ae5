#!/usr/bin/env python3
"""Run original TAR against an unchanged ACGN model plus explicit repair markers.

JSON stdin: {case_id, path, predicate, timeout_seconds, depth}; optional
oracle, build_manifest, java, max_heap, hard_timeout_seconds, log_directory.
One JSON result goes to stdout. No local dataset or oracle code is logged unless
log_directory is explicitly requested; logs belong in private build artifacts.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from scripts.import_exercises import tokens, braced_declaration
from benchmarks.alloy4fun.tar.verify import verify_candidate
from benchmarks.alloy4fun.live.adapter import scratch_root
from runtime_dependencies import clean_java_environment
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_]*\Z')

def prepare_model(source: str, predicate: str, oracle: str) -> str:
    for name in (predicate, oracle):
        if not IDENTIFIER.fullmatch(name):
            raise ValueError('Expected an unqualified Alloy predicate identifier')
    if re.search(r'\b(?:pred|fun|assert|check)\s+__repair\b', source):
        raise ValueError('Source already declares the reserved TAR repair marker')
    # Rename only the original check label, preserving its formula, scopes,
    # module facts, and all other model bytes. Appending a fresh unscoped check
    # would silently discard explicit scopes in a future corpus.
    data = source.encode('utf-8')
    ts = tokens(data)
    original = braced_declaration(ts, b'check', b'correct')
    expected = [predicate.encode(), b'<', b'=', b'>', oracle.encode()]
    if original['bodyTokens'] != expected:
        raise ValueError('Original correctness check does not compare the selected predicates')
    check_index = next(i for i, token in enumerate(ts[:-1]) if token.depth == 0 and token.value == b'check' and ts[i + 1].value == b'correct')
    name = ts[check_index + 1]
    renamed = data[:name.start] + b'__repair' + data[name.end:]
    return renamed.decode('utf-8') + f'\npred __repair {{ {predicate} }}\n'

def run_case(request: dict) -> dict:
    start = time.perf_counter()
    source_path = Path(request['path']).resolve()
    source = source_path.read_bytes().decode('utf-8')
    predicate = request['predicate']
    oracle = request.get('oracle', predicate + 'c')
    depth = int(request.get('depth', 2))
    timeout_seconds = int(request.get('timeout_seconds', 60))
    if depth < 0 or timeout_seconds <= 0:
        raise ValueError('depth must be nonnegative and timeout_seconds positive')
    manifest_path = Path(request.get('build_manifest', ROOT / 'build/benchmarks/tar/manifest.json'))
    manifest = json.loads(manifest_path.read_text())
    result = {
        'case_id': request.get('case_id', source_path.stem), 'method': 'tar',
        'depth_limit': depth, 'timeout_seconds': timeout_seconds,
        'source_sha256': hashlib.sha256(source.encode('utf-8')).hexdigest(),
        'build_manifest_sha256': hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        'native_hint_available': False, 'hint_available': False,
        'verified_correct': None,
        'correctness_scope': 'TAR bounded oracle equivalence with original module facts; no independent recheck',
    }
    with tempfile.TemporaryDirectory(prefix='alloy-tar-', dir=scratch_root()) as temporary:
        preference_root = Path(temporary) / 'java-preferences'
        preference_root.mkdir()
        model = Path(temporary) / 'input.als'
        model.write_text(prepare_model(source, predicate, oracle), encoding='utf-8')
        command = [request.get('java', 'java'), '-Djava.io.tmpdir=' + temporary,
                   '-Djava.util.prefs.userRoot=' + str(preference_root), '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-Xmx' + request.get('max_heap', '1g'), '-cp', manifest['classpath'], 'TarRunner', str(model), str(depth), str(timeout_seconds)]
        # The caller may impose its own process-group deadline. This secondary
        # bound prevents an indefinitely stuck solver when running standalone.
        hard_timeout = float(request.get('hard_timeout_seconds', timeout_seconds + 15))
        native_start = time.perf_counter()
        try:
            completed = subprocess.run(command, capture_output=True, text=True, timeout=hard_timeout,
                                       env=clean_java_environment())
            stdout, stderr, exit_code = completed.stdout, completed.stderr, completed.returncode
        except subprocess.TimeoutExpired as error:
            stdout = error.stdout or b''
            stderr = error.stderr or b''
            stdout = stdout.decode(errors='replace') if isinstance(stdout, bytes) else stdout
            stderr = stderr.decode(errors='replace') if isinstance(stderr, bytes) else stderr
            exit_code = None
            result['status'] = 'outer_timeout'
        result['process_wall_s'] = time.perf_counter() - native_start
        result['exit_code'] = exit_code
        if request.get('log_directory'):
            directory = Path(request['log_directory'])
            directory.mkdir(parents=True, exist_ok=True)
            token = hashlib.sha256(result['case_id'].encode()).hexdigest()
            (directory / (token + '.stdout.txt')).write_text(stdout)
            (directory / (token + '.stderr.txt')).write_text(stderr)
        if 'status' not in result:
            native = None
            # TAR's own CLI emits a final JSON line after optional solver output.
            for line in reversed(stdout.splitlines()):
                try:
                    parsed = json.loads(line)
                    if isinstance(parsed, dict) and 'solved' in parsed:
                        native = parsed
                        break
                except json.JSONDecodeError:
                    pass
            if native is None:
                result['status'] = 'engine_error'
                result['error_class'] = 'unreadable_native_response'
                result['stderr_tail'] = stderr[-1500:]
            else:
                result['native_result'] = native
                elapsed = native.get('elapsed')
                result['search_s'] = elapsed / 1000 if isinstance(elapsed, (int, float)) else None
                result['engine_s'] = native.get('api_seconds')
                solved = native.get('solved') is True
                mutation_count = native.get('depth', 0)
                result['status'] = ('already_correct' if mutation_count == 0 else 'repaired') if solved else ('timeout' if native.get('timed_out') else (('parse_error' if native.get('error') in ('edu.mit.csail.sdg.alloy4.ErrorSyntax', 'edu.mit.csail.sdg.alloy4.ErrorType') else 'engine_error') if native.get('error') else 'no_repair'))
                result['repair_available'] = solved and mutation_count > 0
                trace = native.get('native_trace', [])
                result['native_trace'] = trace
                result['native_hint_available'] = result['hint_available'] = solved and any(isinstance(step.get('hint'), str) and step['hint'].strip() for step in trace)
                result['native_verified_correct'] = solved
                result['candidate_repair'] = native.get('solution') if solved else None
    # Benchmark generation time excludes independent validation, reported
    # separately for every candidate under the same unchanged environment.
    result['wall_s'] = time.perf_counter() - start
    if result.get('candidate_repair') is not None and request.get('verify', True):
        verification = verify_candidate(source_path, predicate, result['candidate_repair'],
            timeout_seconds=float(request.get('validation_timeout_seconds', 15)),
            java=request.get('java', 'java'))
        result['verification'] = verification
        result['verified_correct'] = verification['verified_correct']
        result['hint_available'] = result['native_hint_available'] and verification['verified_correct'] is True
        result['correctness_scope'] = 'Independent production Alloy/SAT4J check with unchanged module facts and original check scope; bounded equivalence'
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path)
    args = parser.parse_args()
    import sys
    request = json.loads(args.request.read_text() if args.request else sys.stdin.read())
    try:
        output = run_case(request)
    except Exception as error:
        output = {'case_id': request.get('case_id'), 'method': 'tar', 'status': 'adapter_error', 'error_class': type(error).__name__, 'hint_available': False, 'native_hint_available': False, 'verified_correct': None}
    print(json.dumps(output, sort_keys=True))

if __name__ == '__main__':
    main()
