"""AP01-C09 append-only evidence: Governance.appendEvidence over a real filesystem."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import evidence_store as store


class EvidenceStoreTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='evidence-store-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = store.new_directory(self.root, 'fixture', 'run', now=0, token='00000001')

    def test_identifier_collision_is_refused(self):
        with self.assertRaises(store.EvidenceCollision):
            store.new_directory(self.root, 'fixture', 'run', now=0, token='00000001')
        first = store.append(self.directory, 'a.json', b'{"a":1}')
        with self.assertRaises(store.EvidenceCollision):
            store.append(self.directory, 'a.json', b'{"a":2}')
        self.assertEqual(store.lookup(self.directory, 'a.json', first), b'{"a":1}')

    def test_append_preserves_existing_and_records_new(self):
        first = store.append(self.directory, 'a.json', b'first')
        second = store.append(self.directory, 'nested/b.json', b'second')
        self.assertEqual(store.lookup(self.directory, 'a.json', first), b'first')
        self.assertEqual(store.lookup(self.directory, 'nested/b.json', second), b'second')

    def test_seal_binds_and_detects_tampering_or_unregistered_files(self):
        entries = {'a.json': store.append(self.directory, 'a.json', b'first')}
        root = store.seal(self.directory, entries)
        self.assertEqual(store.verify(self.directory), root)
        with self.assertRaises(store.EvidenceCollision):
            store.seal(self.directory, entries)  # a sealed manifest is never rewritten
        (self.directory / 'late.json').write_bytes(b'late')
        with self.assertRaisesRegex(store.EvidenceMismatch, 'Unregistered'):
            store.verify(self.directory)
        (self.directory / 'late.json').unlink()
        (self.directory / 'a.json').write_bytes(b'tampered')
        with self.assertRaisesRegex(store.EvidenceMismatch, 'changed'):
            store.verify(self.directory)

    def test_seal_refuses_incomplete_inventory_without_publishing_it(self):
        entries = {'a.json': store.append(self.directory, 'a.json', b'first')}
        store.append(self.directory, 'omitted.json', b'extra')
        with self.assertRaises(store.EvidenceMismatch):
            store.seal(self.directory, entries)
        self.assertFalse((self.directory / store.MANIFEST).exists())

    def test_append_cannot_invalidate_a_sealed_inventory(self):
        entries = {'a.json': store.append(self.directory, 'a.json', b'first')}
        root = store.seal(self.directory, entries)
        with self.assertRaises(store.EvidenceCollision):
            store.append(self.directory, 'later.json', b'late')
        self.assertEqual(store.verify(self.directory), root)

    def test_duplicate_manifest_paths_and_portable_path_aliases_rejected(self):
        for name in ('C:/escape', r'a\..\escape', 'a:stream', 'NUL', 'trailing.', 'a/CON.txt'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                store.append(self.directory, name, b'x')
        h = store.append(self.directory, 'a', b'x')
        (self.directory / store.MANIFEST).write_text('{"a":"' + h + '","a":"' + h + '"}')
        with self.assertRaisesRegex(store.EvidenceMismatch, 'Duplicate'):
            store.verify(self.directory)

    def test_linked_store_and_unregistered_linked_directory_rejected(self):
        alias = self.root / 'alias'
        try:
            alias.symlink_to(self.directory, target_is_directory=True)
        except OSError:
            self.skipTest('Symlink creation unavailable on this host')
        with self.assertRaises(ValueError):
            store.append(alias, 'a', b'x')
        entries = {'a': store.append(self.directory, 'a', b'x')}
        store.seal(self.directory, entries)
        (self.directory / 'linked').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            store.verify(self.directory)

    def test_paths_must_stay_inside_and_unlinked(self):
        for relative in ('../escape', '/absolute', 'a/../b', './a', ''):
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                store.append(self.directory, relative, b'x')
        outside = self.root / 'outside'
        outside.mkdir()
        try:
            (self.directory / 'link').symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest('Symlink creation unavailable on this host')
        with self.assertRaises(ValueError):
            store.append(self.directory, 'link/file', b'x')
        self.assertEqual(list(outside.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
