"""AP01-C07 current-source freshness: Governance.currentVerified over fixture repositories.

Each fixture builds a bound historical record (report, approved manifest,
ledger and independent registry pins) and then mutates the checkout or the
record. The live tests require every registered record to remain intact and the
README table to state the computed classification.
"""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import source_freshness as freshness


def sha(data):
    return hashlib.sha256(data).hexdigest()


class FixtureRepository:
    def __init__(self, directory):
        self.root = Path(directory)
        self.write('app.py', b'print("ok")\n')
        self.write('formal/P/A.lean', b'theorem a : True := trivial\n')
        self.write('scripts/verify.py', b'# verifier\n')
        self.manifest = {path: sha((self.root / path).read_bytes())
                         for path in ('app.py', 'formal/P/A.lean', 'scripts/verify.py')}
        self.root_hash = freshness.json_root(self.manifest)
        self.write('evidence/manifest.json', json.dumps(self.manifest, indent=1).encode())
        self.report = {'status': 'VERIFIED', 'inputRootHash': self.root_hash,
                       'claims': [{'verifier': {'id': 'V', 'sha256': self.manifest['scripts/verify.py']}}]}
        self.save_report()

    def write(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def save_report(self):
        data = json.dumps(self.report).encode()
        self.write('evidence/report.json', data)
        self.write('ledger.json', json.dumps({'obligations': [
            {'id': 'X', 'status': 'VERIFIED', 'reportSha256': sha(data), 'inputRootHash': self.root_hash}]}).encode())

    def replace_inventory(self, inventory):
        """Build another independently bound fixture, never rewrite live evidence."""
        self.manifest = inventory
        self.root_hash = freshness.json_root(inventory)
        self.write('evidence/manifest.json', json.dumps(inventory, indent=1).encode())
        self.report['inputRootHash'] = self.root_hash
        self.save_report()

    def entry(self, **overrides):
        entry = {'id': 'X', 'ledger': {'path': 'ledger.json', 'obligation': 'X'},
                 'report': 'evidence/report.json',
                 'reportSha256': sha((self.root / 'evidence/report.json').read_bytes()),
                 'inventory': {'kind': 'manifest', 'path': 'evidence/manifest.json',
                               'sha256': sha((self.root / 'evidence/manifest.json').read_bytes())},
                 'root': {'field': 'inputRootHash', 'sha256': self.root_hash},
                 'verifier': {'path': 'scripts/verify.py', 'sha256': self.manifest['scripts/verify.py']},
                 'completeProofDirectories': ['formal/P']}
        entry.update(overrides)
        return entry


class FreshnessModelTests(unittest.TestCase):
    def setUp(self):
        base = ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='freshness-', dir=base)
        self.addCleanup(temporary.cleanup)
        self.repo = FixtureRepository(temporary.name)

    def classify(self, entry=None):
        return freshness.classify(self.repo.root, entry or self.repo.entry())

    def test_complete_equality_is_current(self):
        result = self.classify()
        self.assertEqual((result['record'], result['current']), ('VALID', 'CURRENT'))
        self.assertEqual(result['approvedEntries'], 3)

    def test_changed_entry_is_stale(self):
        self.repo.write('app.py', b'print("changed")\n')
        result = self.classify()
        self.assertEqual((result['record'], result['current'], result['changed']), ('VALID', 'STALE', ['app.py']))

    def test_missing_entry_is_stale(self):
        (self.repo.root / 'app.py').unlink()
        result = self.classify()
        self.assertEqual((result['current'], result['missing']), ('STALE', ['app.py']))

    def test_extra_proof_source_is_stale(self):
        self.repo.write('formal/P/Forged.lean', b'axiom forged : False\n')
        result = self.classify()
        self.assertEqual((result['current'], result['extra']), ('STALE', ['formal/P/Forged.lean']))

    def test_nested_unregistered_proof_source_is_stale(self):
        self.repo.write('formal/P/nested/deeper/Forged.lean', b'axiom forged : False\n')
        result = self.classify()
        self.assertEqual((result['record'], result['current'], result['extra']),
                         ('VALID', 'STALE', ['formal/P/nested/deeper/Forged.lean']))

    def test_directory_with_only_nested_registered_source_is_complete(self):
        old = self.repo.root / 'formal/P/A.lean'
        self.repo.write('formal/P/nested/A.lean', old.read_bytes())
        old.unlink()
        inventory = dict(self.repo.manifest)
        inventory['formal/P/nested/A.lean'] = inventory.pop('formal/P/A.lean')
        self.repo.replace_inventory(inventory)
        self.assertEqual(self.classify()['current'], 'CURRENT')
        self.repo.write('formal/P/another/B.lean', b'axiom hidden : False\n')
        self.assertEqual(self.classify()['current'], 'STALE')

    def test_noncanonical_inventory_aliases_are_invalid_even_with_consistent_pins(self):
        original = dict(self.repo.manifest)
        aliases = ('./app.py', 'folder/../app.py', 'folder//app.py', 'app.py/',
                   '/app.py', 'C:/app.py', 'C:app.py', 'C:\\app.py',
                   '\\\\server\\share\\app.py', 'folder\\app.py', 'app.py\x00')
        for alias in aliases:
            with self.subTest(alias=alias):
                inventory = dict(original)
                inventory[alias] = inventory.pop('app.py')
                self.repo.replace_inventory(inventory)
                result = self.classify()
                self.assertEqual(result['record'], 'INVALID')
                self.assertIn('repository-relative', result['reason'])

    def test_noncanonical_complete_directory_is_invalid(self):
        for directory in ('./formal/P', 'formal//P', 'formal\\P', 'C:/formal/P'):
            with self.subTest(directory=directory):
                result = self.classify(self.repo.entry(completeProofDirectories=[directory]))
                self.assertEqual(result['record'], 'INVALID')

    def make_symlink(self, source, target, *, directory=False):
        try:
            source.symlink_to(target, target_is_directory=directory)
        except (NotImplementedError, OSError):
            self.skipTest('Creating symlinks is unavailable on this host')

    def test_parent_symlink_cannot_make_identical_input_current(self):
        original = self.repo.root / 'formal/P'
        destination = self.repo.root / 'relocated-proof'
        original.rename(destination)
        self.make_symlink(original, destination, directory=True)
        result = self.classify()
        self.assertEqual((result['record'], result['current']), ('VALID', 'STALE'))
        self.assertIn('formal/P/A.lean', result['missing'])
        self.assertIn('formal/P', result['unavailable'])

    def test_file_symlink_cannot_make_identical_input_current(self):
        original = self.repo.root / 'app.py'
        destination = self.repo.root / 'relocated-app.py'
        original.rename(destination)
        self.make_symlink(original, destination)
        result = self.classify()
        self.assertEqual((result['current'], result['missing']), ('STALE', ['app.py']))

    def test_linked_nested_directory_is_not_silently_ignored(self):
        self.repo.write('outside/Unregistered.lean', b'axiom hidden : False\n')
        self.make_symlink(self.repo.root / 'formal/P/nested', self.repo.root / 'outside', directory=True)
        result = self.classify()
        self.assertEqual(result['current'], 'STALE')
        self.assertIn('formal/P/nested', result['unavailable'])

    def test_unreadable_current_input_is_stale_and_history_stays_valid(self):
        original_digest = freshness.digest

        def fail_source(path):
            if path == self.repo.root / 'app.py':
                raise PermissionError('synthetic unreadable source')
            return original_digest(path)

        with patch.object(freshness, 'digest', side_effect=fail_source):
            result = self.classify()
        self.assertEqual((result['record'], result['current'], result['missing']),
                         ('VALID', 'STALE', ['app.py']))
        self.assertEqual(result['historical']['status'], 'VERIFIED')

    def test_unreadable_directory_scan_cannot_be_current(self):
        original_iterdir = Path.iterdir

        def fail_proof_scan(path):
            if path == self.repo.root / 'formal/P':
                raise PermissionError('synthetic unreadable proof directory')
            return original_iterdir(path)

        with patch.object(Path, 'iterdir', fail_proof_scan):
            result = self.classify()
        self.assertEqual((result['record'], result['current'], result['unavailable']),
                         ('VALID', 'STALE', ['formal/P']))

    def test_unreadable_extra_proof_is_still_reported_extra(self):
        name = 'formal/P/nested/Extra.lean'
        self.repo.write(name, b'axiom hidden : False\n')
        original_digest = freshness.digest

        def fail_extra(path):
            if path == self.repo.root / name:
                raise PermissionError('synthetic unreadable proof source')
            return original_digest(path)

        with patch.object(freshness, 'digest', side_effect=fail_extra):
            result = self.classify()
        self.assertEqual((result['record'], result['current'], result['extra'], result['unavailable']),
                         ('VALID', 'STALE', [name], [name]))

    def test_duplicate_inventory_path_invalidates_record(self):
        text = (self.repo.root / 'evidence/manifest.json').read_text()
        duplicated = text.replace('"app.py"', '"app.py": "' + '0' * 64 + '", "app.py"', 1)
        self.repo.write('evidence/manifest.json', duplicated.encode())
        result = self.classify(self.repo.entry())
        self.assertEqual(result['record'], 'INVALID')
        self.assertIn('Duplicate', result['reason'])

    def test_empty_inventory_is_never_verified(self):
        pinned = self.repo.entry()
        self.repo.write('evidence/manifest.json', b'{}')
        pinned['inventory']['sha256'] = sha(b'{}')
        result = self.classify(pinned)
        self.assertEqual(result['record'], 'INVALID')
        self.assertIn('Empty', result['reason'])

    def test_self_attested_manifest_fails_the_independent_pins(self):
        self.repo.write('app.py', b'print("changed")\n')
        regenerated = dict(self.repo.manifest, **{'app.py': sha(b'print("changed")\n')})
        pinned = self.repo.entry()
        self.repo.write('evidence/manifest.json', json.dumps(regenerated).encode())
        self.assertEqual(self.classify(pinned)['record'], 'INVALID')

    def test_unbound_record_variants_are_invalid(self):
        mutations = {
            'not passed': lambda r: r.update(status='BLOCKED'),
            'root': lambda r: r.update(inputRootHash='0' * 64),
            'verifier': lambda r: r['claims'][0]['verifier'].update(sha256='1' * 64),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                original = json.loads(json.dumps(self.repo.report))
                mutate(self.repo.report)
                self.repo.save_report()
                self.assertEqual(self.classify(self.repo.entry())['record'], 'INVALID')
                self.repo.report = original
                self.repo.save_report()
        ledger = self.repo.root / 'ledger.json'
        ledger.write_text(ledger.read_text().replace('"VERIFIED"', '"OPEN"'))
        self.assertEqual(self.classify(self.repo.entry())['record'], 'INVALID')

    def test_source_drift_does_not_rewrite_history(self):
        before = {path: (self.repo.root / path).read_bytes()
                  for path in ('evidence/report.json', 'evidence/manifest.json', 'ledger.json')}
        self.repo.write('app.py', b'drift\n')
        result = self.classify()
        self.assertEqual(result['current'], 'STALE')
        self.assertEqual(result['historical']['status'], 'VERIFIED')
        self.assertEqual(result['historical']['root'], self.repo.root_hash)
        for path, data in before.items():
            self.assertEqual((self.repo.root / path).read_bytes(), data)


class RepositoryFreshnessTests(unittest.TestCase):
    def test_every_registered_historical_record_is_intact(self):
        report = freshness.run(ROOT)
        self.assertEqual(report['gate'], 'PASS', report)
        self.assertEqual({result['id'] for result in report['closures']}, {'TRF-00', 'TRF-01', 'AP01'})
        for result in report['closures']:
            self.assertEqual(result['historical']['status'], 'VERIFIED')
            self.assertIn(result['current'], ('CURRENT', 'STALE'))

    def test_readme_states_the_computed_classification(self):
        readme = (ROOT / 'README.md').read_text()
        self.assertIn(freshness.readme_block(freshness.run(ROOT)), readme)


if __name__ == '__main__':
    unittest.main()
