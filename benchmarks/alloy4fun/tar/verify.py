#!/usr/bin/env python3
"""Independently validate a complete TAR repair using production Alloy/SAT4J."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from scripts.import_exercises import tokens, braced_declaration
from benchmarks.alloy4fun.live.adapter import scratch_root
from runtime_dependencies import clean_java_environment

BUILD = ROOT / 'build/benchmarks/tar/verification-classes'
LIB = ROOT / 'vendor/acgn/lib'
CLASSPATH = os.pathsep.join(map(str, (BUILD, LIB / 'alloy.jar', LIB / 'json-java.jar')))

def build_verifier():
    BUILD.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('VerifyRepair.java')
    subprocess.run(['javac', '-cp', os.pathsep.join(map(str, (LIB / 'alloy.jar', LIB / 'json-java.jar'))), '-d', str(BUILD), str(source)],
                   check=True, env=clean_java_environment())
    artifact = {
        'solver': 'SAT4J', 'no_overflow': False,
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'alloy_runtime_sha256': hashlib.sha256((LIB / 'alloy.jar').read_bytes()).hexdigest(),
        'json_runtime_sha256': hashlib.sha256((LIB / 'json-java.jar').read_bytes()).hexdigest(),
        'class_sha256': hashlib.sha256((BUILD / 'VerifyRepair.class').read_bytes()).hexdigest(),
        'classpath': CLASSPATH,
    }
    (BUILD.parent / 'verification-manifest.json').write_text(json.dumps(artifact, indent=2) + '\n')
    return artifact

def replace_candidate(source: bytes, predicate: str, candidates: dict) -> bytes:
    if not isinstance(candidates, dict) or set(candidates) != {predicate}:
        raise ValueError('A repair must modify exactly the selected learner predicate')
    body = candidates[predicate]
    if not isinstance(body, str):
        raise ValueError('Repair body must be text')
    declaration = braced_declaration(tokens(source), b'pred', predicate.encode('ascii'))
    replacement = b'\n' + body.encode('utf-8') + b'\n'
    # Validate the proposed body cannot terminate its predicate and inject new
    # top-level environment paragraphs. The production Alloy parser follows.
    body_tokens = tokens(b'{' + replacement + b'}')
    if body_tokens[0].value != b'{' or body_tokens[-1].value != b'}' or any(token.depth == 0 for token in body_tokens[1:-1]):
        raise ValueError('Repair body escapes its declaration')
    return source[:declaration['bodyStart']] + replacement + source[declaration['bodyEnd']:]

def verify_candidate(source_path, predicate, candidates, timeout_seconds=15, java='java'):
    start = time.perf_counter()
    try:
        source = Path(source_path).read_bytes()
        modified = replace_candidate(source, predicate, candidates)
        with tempfile.TemporaryDirectory(prefix='alloy-tar-verify-', dir=scratch_root()) as directory:
            path = Path(directory) / 'input.als'
            path.write_bytes(modified)
            completed = subprocess.run([java, '-Djava.io.tmpdir=' + directory,
                '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-Xmx512m', '-cp', CLASSPATH,
                'VerifyRepair', str(path)], capture_output=True, text=True, timeout=timeout_seconds,
                env=clean_java_environment())
            output = json.loads(completed.stdout.strip().splitlines()[-1])
            output['validation_process_wall_s'] = time.perf_counter() - start
            return output
    except subprocess.TimeoutExpired:
        return {'status': 'validation_timeout', 'verified_correct': None, 'validation_process_wall_s': time.perf_counter() - start}
    except Exception as error:
        return {'status': 'validation_error', 'verified_correct': None, 'error_class': type(error).__name__, 'validation_process_wall_s': time.perf_counter() - start}

if __name__ == '__main__':
    print(json.dumps(build_verifier(), sort_keys=True))
