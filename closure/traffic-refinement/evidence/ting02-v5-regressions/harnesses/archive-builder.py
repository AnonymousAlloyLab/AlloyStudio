"""Preserve the already completed finite regressions; never execute their suites."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re


ROOT = Path.cwd().resolve()
WORK = ROOT / 'build/trf-closure/ting02-v5-preserve'
ARCHIVE = WORK / 'stage'
BASE = ROOT / 'build/trf-closure/ting02-python-20261004T190652Z'
BROWSER = ROOT / 'build/trf-closure/trf01-browser/snapshot-20261004T185251Z'
FUNCTIONAL = ROOT / 'build/trf-closure/trf01-regressions/functional-invariance-30eb95afe161.json'
RUNTIME = ROOT / 'build/trf-closure/trf01-composition/runtime-check.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked(root, name):
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or '..' in path.parts:
        raise ValueError('Unsafe input path')
    if any(part in {'.git', 'secrets', 'admin.local.json', 'openai.local.json'}
           or part.startswith('.env') for part in path.parts):
        raise ValueError('Private input is forbidden')
    result = root
    for part in path.parts:
        result = result / part
        if result.is_symlink():
            raise ValueError('Linked input path')
    return result


# Gate all archive creation on the completed, unchanged passing suite.
report_raw = (BASE / 'report.json').read_bytes()
report = json.loads(report_raw)
assert report['status'] == 'PASS' and report['exitCode'] == 0
assert report['changedInputPaths'] == [] and report['providersDisabled'] is True
manifest_raw = (BASE / 'inputs.json').read_bytes()
manifest = json.loads(manifest_raw)
log_raw = (BASE / 'unittest.txt').read_bytes()
assert sha(manifest_raw) == report['inputManifestSha256']
assert sha(log_raw) == report['logSha256']
count = re.search(rb'Ran ([0-9]+) tests? in ', log_raw)
assert count and int(count[1]) == report['tests'] and report['tests'] > 0
b = json.loads((BROWSER / 'report.json').read_bytes())
f = json.loads(FUNCTIONAL.read_bytes())
runtime = json.loads(RUNTIME.read_bytes())
assert all(record['status'] == 'PASS' for record in [b, f, runtime])
assert b['sourceSnapshotStable'] is True and b['workspaceChangesSinceCopy'] == []
assert b['providerDisabled'] is True and b['privateConfigsCopied'] is False
assert b['engineRecompiled'] is False
assert f['sourceIdentityStable'] is True and f['execution']['providersDisabled'] is True
assert f['comparison'] == {'casesPerArm': 23, 'failures': [], 'status': 'PASS'}
assert len(f['arms']) == 3 and all(len(arm['rows']) == 23 for arm in f['arms'])
assert runtime['errors'] == [] and runtime['engine'] == {'checks': 378, 'status': 'PASS'}
commands = {item['command']: item for item in b['commands']}
assert len(commands) == 3 and all(item['exitCode'] == 0 for item in commands.values())
assert commands['browser']['result']['checks'] == 77
assert commands['dashboard']['result']['checks'] == 7
assert commands['package']['result']['privateArchive'] is True
for record, field in [(b, 'sourceFiles'), (f, 'sources')]:
    assert sha(json.dumps(record[field], sort_keys=True, separators=(',', ':')).encode()) == record['sourceIdentitySha256']
    assert all(manifest.get(name) == digest for name, digest in record[field].items())
runtime_inputs = {item['path']: item['sha256'] for item in runtime['classes'] + runtime['dependencies']}
assert all(manifest.get(name) == digest for name, digest in runtime_inputs.items())
for name, digest in b['sourceFiles'].items():
    assert sha(checked(BROWSER, name).read_bytes()) == digest, 'Changed browser input: ' + name
for label, item in commands.items():
    stdout = (BROWSER / (label + '.stdout')).read_text()
    assert json.loads(stdout.strip().splitlines()[-1]) == item['result']
assert not ARCHIVE.exists(), 'Refusing to replace an archive stage'
ARCHIVE.mkdir()
artifacts = {}
objects = {}
origins = {}


def artifact(source, name):
    data = source.read_bytes()
    target = ARCHIVE / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    artifacts[name] = {'sha256': sha(data), 'bytes': len(data),
                       'origin': source.relative_to(ROOT).as_posix()}


for name in ['report.json', 'inputs.json', 'unittest.txt']:
    artifact(BASE / name, 'artifacts/python-final/' + name)
for name, digest in manifest.items():
    assert re.fullmatch('[0-9a-f]{64}', digest), 'Invalid source digest'
    assert not name.endswith('.zip'), 'ZIP payloads are excluded'
    source = checked(BASE / 'source', name)
    data = source.read_bytes()
    assert sha(data) == digest, 'Changed staged source: ' + name
    origins.setdefault(digest, source)
    if name.startswith('closure/traffic-refinement/evidence/'):
        historical = checked(ROOT, name)
        assert historical.read_bytes() == data, 'Historical evidence mismatch: ' + name
        objects.setdefault(digest, {'kind': 'historical-evidence', 'path': name,
                                    'bytes': len(data)})
for digest, source in origins.items():
    if digest in objects:
        continue
    data = source.read_bytes()
    assert sha(data) == digest
    target = ARCHIVE / 'objects' / digest
    target.parent.mkdir(exist_ok=True)
    target.write_bytes(data)
    objects[digest] = {'kind': 'local-object', 'path': 'objects/' + digest,
                       'bytes': len(data)}
for name in ['report.json', 'browser.stdout', 'browser.stderr', 'dashboard.stdout',
             'dashboard.stderr', 'package.stdout', 'package.stderr']:
    artifact(BROWSER / name, 'artifacts/browser/' + name)
artifact(FUNCTIONAL, 'artifacts/functional/report.json')
artifact(RUNTIME, 'artifacts/runtime/report.json')
for source, name in [
        (ROOT / 'build/trf-closure/trf01-composition/full_regression.py', 'python-runner.py'),
        (ROOT / 'build/trf-closure/trf01-browser/run-isolated.py', 'browser-runner.py'),
        (Path(__file__).resolve(), 'archive-builder.py')]:
    artifact(source, 'harnesses/' + name)
artifact(WORK / 'replay.py', 'replay.py')
readme = (WORK / 'README.template.md').read_text().replace('@PYTHON_TESTS@', str(report['tests']))
readme = readme.replace('@INPUT_COUNT@', str(len(manifest)))
(ARCHIVE / 'README.md').write_text(readme)
raw = (ARCHIVE / 'README.md').read_bytes()
artifacts['README.md'] = {'sha256': sha(raw), 'bytes': len(raw),
                          'origin': 'authored preservation documentation'}
runs = {
    'python-final': {'manifestArtifact': 'artifacts/python-final/inputs.json',
                     'reportArtifact': 'artifacts/python-final/report.json',
                     'status': 'PASS', 'tests': report['tests']},
    'browser': {'manifestArtifact': 'artifacts/browser/report.json',
                'manifestFields': ['sourceFiles'], 'reportArtifact': 'artifacts/browser/report.json',
                'status': 'PASS', 'browserChecks': 77, 'dashboardChecks': 7,
                'privatePackageArchived': False},
    'functional': {'replayInputSet': 'python-final',
                   'recordedManifestArtifact': 'artifacts/functional/report.json',
                   'recordedManifestFields': ['sources'],
                   'reportArtifact': 'artifacts/functional/report.json',
                   'status': 'PASS', 'casesPerArm': 23, 'arms': 3},
    'runtime': {'replayInputSet': 'python-final', 'reportArtifact': 'artifacts/runtime/report.json',
                'status': 'PASS', 'engineChecks': 378},
}
statistics = {'uniquePayloads': len(objects),
              'newPayloads': sum(item['kind'] == 'local-object' for item in objects.values()),
              'newPayloadBytes': sum(item['bytes'] for item in objects.values() if item['kind'] == 'local-object'),
              'referencedHistoricalPayloads': sum(item['kind'] == 'historical-evidence' for item in objects.values()),
              'finalPythonInputPaths': len(manifest), 'browserInputPaths': len(b['sourceFiles']),
              'functionalRecordedInputPaths': len(f['sources']), 'runtimeRecordedInputPaths': len(runtime_inputs)}
index = {'schemaVersion': 1, 'kind': 'finite-regression-preservation', 'artifacts': artifacts,
         'runs': runs, 'objects': objects, 'closureClaim': False,
         'previousArchive': 'closure/traffic-refinement/evidence/ting02-final-regressions/index.json',
         'limitations': ['Finite Linux cases, not universal equivalence, deployment, review approval, or closure.',
                        'Git history, toolchains and Playwright installation are external.',
                        'Historical evidence references must remain available with identical bytes.',
                        'Private configs, environment files and private IIS ZIPs are excluded.'],
         'statistics': statistics}
raw = (json.dumps(index, sort_keys=True, indent=2) + '\n').encode()
(ARCHIVE / 'index.json').write_bytes(raw)
(ARCHIVE / 'index.sha256').write_text(sha(raw) + '  index.json\n')
print(json.dumps({'stagedArchive': ARCHIVE.relative_to(ROOT).as_posix(),
                  'indexSha256': sha(raw), **statistics}, sort_keys=True))
