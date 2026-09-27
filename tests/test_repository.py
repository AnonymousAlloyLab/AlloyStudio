"""Finite delivery provenance and credential-pattern checks."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.prepare_private_data import validate_pair

ROOT = Path(__file__).resolve().parents[1]


class RepositoryTests(unittest.TestCase):
    def test_bundled_catalogue_and_all_candidate_source_witnesses_validate(self):
        catalogue, pools = validate_pair(
            (ROOT / 'exercises/catalogue.json').read_bytes(),
            (ROOT / 'exercises/correct-pools.json').read_bytes())
        self.assertEqual(len(catalogue['exercises']), 181)
        self.assertEqual(len(pools['pools']), 181)
        self.assertEqual(sum(len(pool['candidates']) for pool in pools['pools']), 7731)

    def test_vendored_framework_matches_snapshot(self):
        vendor = ROOT / 'vendor/acgn'
        snapshot = json.loads((vendor / 'snapshot.json').read_text())
        expected = {entry['path']: entry['sha256'] for entry in snapshot['files']}
        actual = {p.relative_to(vendor).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in vendor.rglob('*') if p.is_file() and p.name != 'snapshot.json'}
        self.assertEqual(actual, expected)
        self.assertEqual(snapshot['commit'], '1e2667351532b0c632166fa21ae5fbc7308a8fe7')
        patches = snapshot['localPatches']
        self.assertEqual([patch['id'] for patch in patches], ['parser-source-origins'])
        files = patches[0]['files']
        self.assertEqual({entry['path'] for entry in files}, {
            'src/is/fivefivefive/ACGN/asg/AugmentedNode.java',
            'src/is/fivefivefive/ACGN/visitor/MASGVisitor.java',
            'src/is/fivefivefive/CanDis/ir/IRAgent.java',
            'src/is/fivefivefive/CanDis/core/EGraphNode.java',
            'src/is/fivefivefive/CanDis/core/NormalForm.java',
            'src/is/fivefivefive/CanDis/core/QuantiVar.java',
        })
        self.assertEqual(len(files), 6)
        for entry in files:
            self.assertRegex(entry['baseSha256'], r'^[0-9a-f]{64}$')
            self.assertNotEqual(entry['baseSha256'], expected[entry['path']])

    def test_no_credential_files_or_token_patterns_in_delivery_source(self):
        token = re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}')
        excluded = {'.git', 'node_modules', 'build', '__pycache__', 'runs', 'secrets'}
        for path in ROOT.rglob('*'):
            relative = path.relative_to(ROOT)
            if set(relative.parts) & excluded or not path.is_file(): continue
            # Local credentials are outside delivery source. Never open them;
            # the separate Git witness checks their exclusion from a commit.
            if (path.name == 'openai.local.json' or path.name == '.env'
                    or path.name.startswith('.env.') and path.name != '.env.example'):
                continue
            self.assertNotIn(path.suffix, ('.key', '.pem'), 'Credential file in source tree')
            self.assertIsNone(token.search(path.read_bytes()), 'Token-shaped credential in ' + str(relative))

    def test_git_admits_bundled_data_and_excludes_local_credentials(self):
        # A closure snapshot has no .git. Exercise the actual ignore rules in a
        # new repository without reading this checkout's Git config or history.
        with tempfile.TemporaryDirectory(prefix='alloy-ignore-') as directory:
            root = Path(directory)
            shutil.copy2(ROOT / '.gitignore', root / '.gitignore')
            environment = {'HOME': directory, 'USERPROFILE': directory,
                           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull}
            git = shutil.which('git')
            self.assertIsNotNone(git, 'The source-distribution witness requires Git.')
            subprocess.run([git, 'init', '--quiet', '--template=', directory],
                           env=environment, capture_output=True, check=True, timeout=10)
            admitted = {'exercises/catalogue.json', 'exercises/correct-pools.json', '.env.example'}
            excluded = {'.env', '.env.local', 'openai.local.json', 'secrets/openai.key',
                        'nested/token.key', 'nested/certificate.pem', 'closure/runs/result.json'}
            result = subprocess.run([git, 'check-ignore', '--no-index', '--stdin'],
                                    cwd=root, env=environment, input='\n'.join(sorted(admitted | excluded)) + '\n',
                                    text=True, capture_output=True, check=False, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(set(result.stdout.splitlines()), excluded)


if __name__ == '__main__': unittest.main()
