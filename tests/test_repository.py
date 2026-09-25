"""Finite delivery provenance and credential-pattern checks."""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RepositoryTests(unittest.TestCase):
    def test_vendored_framework_matches_snapshot(self):
        vendor = ROOT / 'vendor/acgn'
        snapshot = json.loads((vendor / 'snapshot.json').read_text())
        expected = {entry['path']: entry['sha256'] for entry in snapshot['files']}
        actual = {p.relative_to(vendor).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in vendor.rglob('*') if p.is_file() and p.name != 'snapshot.json'}
        self.assertEqual(actual, expected)
        self.assertEqual(snapshot['commit'], '1e2667351532b0c632166fa21ae5fbc7308a8fe7')

    def test_no_credential_files_or_token_patterns_in_delivery_source(self):
        token = re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}')
        excluded = {'.git', 'node_modules', 'build', '__pycache__', 'runs', 'secrets'}
        for path in ROOT.rglob('*'):
            relative = path.relative_to(ROOT)
            if set(relative.parts) & excluded or not path.is_file(): continue
            if path.name == 'openai.local.json': continue
            self.assertNotEqual(path.suffix, '.key', 'Credential file in source tree')
            self.assertIsNone(token.search(path.read_bytes()), 'Token-shaped credential in ' + str(relative))
        ignore = (ROOT / '.gitignore').read_text()
        for pattern in ('*.key', '.env', 'openai.local.json', 'secrets/', 'exercises/catalogue.json', 'closure/runs/'):
            self.assertIn(pattern, ignore.splitlines())


if __name__ == '__main__': unittest.main()
