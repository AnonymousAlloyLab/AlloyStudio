"""Real admin UI, mocked offline API: no credentials or provider network required."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AdminBrowserTests(unittest.TestCase):
    def test_real_admin_assets_against_offline_api(self):
        result = subprocess.run(['node', 'tests/admin.mjs'], cwd=ROOT, capture_output=True,
                                text=True, timeout=120, env=dict(os.environ, OPENAI_DISABLED='1'))
        self.assertEqual(result.returncode, 0,
                         'Admin browser contract failed; run node tests/admin.mjs locally.')
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['checks'], 14)
        self.assertEqual(len(set(report['passed'])), 14)


if __name__ == '__main__':
    unittest.main()
