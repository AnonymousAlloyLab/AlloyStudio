#!/usr/bin/env python3
"""Compile unmodified TAR repair sources over its bundled Alloy runtime."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import sys

ROOT = Path(__file__).resolve().parents[3]
ANNOTATION_URL = 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/org.eclipse.jdt.annotation/2.2.600/org.eclipse.jdt.annotation-2.2.600.jar'
ANNOTATION_SHA256 = 'db9cba445a74fc4e5d6badda73e0b7fbfb8ca1383d7ecdcc0d5c12debc5503a8'

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tar-root', type=Path, default=Path('/home/augustus/lp_baselines/TAR'))
    parser.add_argument('--output', type=Path, default=ROOT / 'build/benchmarks/tar')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--java8-rt', type=Path, default=Path('/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/rt.jar'))
    args = parser.parse_args()
    upstream = args.tar_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    dependency = output / 'org.eclipse.jdt.annotation-2.2.600.jar'
    if not dependency.exists():
        with urllib.request.urlopen(ANNOTATION_URL, timeout=60) as response:
            payload = response.read()
        if hashlib.sha256(payload).hexdigest() != ANNOTATION_SHA256:
            raise RuntimeError('Annotation dependency checksum mismatch')
        dependency.write_bytes(payload)
    if sha(dependency) != ANNOTATION_SHA256:
        raise RuntimeError('Annotation dependency checksum mismatch')
    archive = upstream / 'beafix_benchmarks/arepair/atr.jar'
    sources = sorted((upstream / 'org.alloytools.alloy.core/src/main/java/pt/haslab').rglob('*.java'))
    sources.append(upstream / 'org.alloytools.alloy.application/src/main/java/edu/mit/csail/sdg/alloy4whole/RepairCLI.java')
    runner = Path(__file__).with_name('TarRunner.java')
    json_archive = ROOT / 'vendor/acgn/lib/json-java.jar'
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    command = [args.javac, '-source', '8', '-target', '8', '-bootclasspath', str(args.java8_rt), '-cp', os.pathsep.join(map(str, (archive, dependency, json_archive))), '-d', str(classes), *map(str, sources), str(runner)]
    subprocess.run(command, check=True)
    metadata = {
        'schema_version': 1,
        'semantic_policy': {'no_overflow': False, 'upstream_default_no_overflow': True,
            'configuration_api': 'A4Preferences.NoOverflow.set(false)',
            'preference_isolation': 'per-worker java.util.prefs.userRoot temporary directory',
            'repair_solver': 'MiniSatJNI', 'validation_solver': 'SAT4J',
            'scope_policy': 'preserve original check scopes and module facts'},
        'upstream': str(upstream),
        'git_commit': subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip(),
        'git_tracked_diff': subprocess.check_output(['git', '-C', str(upstream), 'diff', '--stat', 'HEAD'], text=True).strip(),
        'runtime_archive': str(archive),
        'runtime_archive_sha256': sha(archive),
        'runner_source_sha256': sha(runner),
        'json_archive_sha256': sha(json_archive),
        'runtime_embedded_git_sha': '8e2b85517c8453ba8c7663bb6b3aed5fe51f68ea',
        'annotation_url': ANNOTATION_URL,
        'annotation_sha256': sha(dependency),
        'java8_rt_sha256': sha(args.java8_rt),
        'source_sha256': {str(p.relative_to(upstream)): sha(p) for p in sources},
        'class_sha256': {str(p.relative_to(classes)): sha(p) for p in sorted(classes.rglob('*.class'))},
        'compiler': subprocess.check_output([args.javac, '-version'], text=True, stderr=subprocess.STDOUT).strip(),
        'classpath': os.pathsep.join(map(str, (classes, archive, json_archive))),
        'build_command': command,
    }
    (output / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    sys.path.insert(0, str(ROOT))
    from benchmarks.alloy4fun.tar.verify import build_verifier
    build_verifier()
    print(output / 'manifest.json')

if __name__ == '__main__':
    main()
