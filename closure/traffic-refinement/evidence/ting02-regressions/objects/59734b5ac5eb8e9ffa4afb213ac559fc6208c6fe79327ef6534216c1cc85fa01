"""Negative controls for the offline proof gate; not implementation proofs."""
import hashlib
import json
import os
import shutil
from pathlib import Path
import sys
import tempfile
import unittest
import subprocess
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import lean_offline
import verify_lean as gate


class LeanGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value) if not isinstance(value, str) else value)
        return path

    def test_missing_toolchain_does_not_invoke_elan_or_install(self):
        self.write('lean-toolchain', 'leanprover/lean4:v4.34.1')
        self.write('formal/lean-toolchain', 'leanprover/lean4:v4.34.1')
        with patch.dict(os.environ, {'ELAN_HOME': str(self.root / 'absent')}), \
                patch('subprocess.run') as run:
            with self.assertRaises(FileNotFoundError):
                lean_offline.installed_toolchain(self.root)
            run.assert_not_called()

    def test_unpinned_or_disagreeing_toolchain_rejected(self):
        self.write('lean-toolchain', 'leanprover/lean4:stable')
        self.write('formal/lean-toolchain', 'leanprover/lean4:stable')
        with self.assertRaises(ValueError):
            lean_offline.installed_toolchain(self.root)
        self.write('formal/lean-toolchain', 'leanprover/lean4:v4.34.1')
        with self.assertRaises(ValueError):
            lean_offline.installed_toolchain(self.root)

    def test_environment_excludes_credentials_plugins_and_shell_hooks(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'fixture-only', 'PYTHONPATH': '/attack',
                                     'LD_PRELOAD': '/attack.so', 'LEAN_PATH': '/attack',
                                     'BASH_ENV': '/attack', 'GH_TOKEN': 'fixture-only'}):
            clean = lean_offline.clean_environment(self.root, self.root / 'objects')
        for name in ('OPENAI_API_KEY', 'PYTHONPATH', 'LD_PRELOAD', 'BASH_ENV', 'GH_TOKEN'):
            self.assertNotIn(name, clean)
        self.assertEqual(clean['LEAN_PATH'], str(self.root / 'objects'))
        self.assertEqual(clean['OPENAI_DISABLED'], '1')

    def test_no_online_fallback_when_namespace_unavailable(self):
        with patch.object(lean_offline.sys, 'platform', 'darwin'):
            with self.assertRaises(RuntimeError):
                lean_offline.isolated_command(['lean'])
        with patch.object(lean_offline.sys, 'platform', 'linux'), \
                patch.object(lean_offline.shutil, 'which', return_value='/usr/bin/unshare'):
            self.assertEqual(lean_offline.isolated_command(['lean']),
                ['/usr/bin/unshare', '--user', '--map-root-user', '--net', '--', 'lean'])

    def test_bridge_runtime_inventory_matches_host_path_resolution(self):
        # Construct the review witness: a different host PATH from the clean
        # child environment must still bind the executable the bridge runs.
        executable = 'node.exe' if os.name == 'nt' else 'node'
        shadow = self.write('shadow/' + executable, '#!/bin/sh\nprintf SHADOW_RUNTIME_EXECUTED\n')
        shadow.chmod(0o755)
        with patch.dict(os.environ, {'PATH': str(shadow.parent) + os.pathsep + os.defpath}):
            selected = gate.bridge_runtime_path('node')
            self.assertEqual(selected, Path(shutil.which('node')).resolve())
            self.assertEqual(selected, shadow.resolve())
            if os.name == 'posix':
                output = subprocess.check_output([str(selected), '--version'],
                    env=lean_offline.clean_environment(self.root), text=True)
                self.assertEqual(output, 'SHADOW_RUNTIME_EXECUTED')

    def test_changed_bridge_runtime_path_or_binary_is_rejected(self):
        executable = 'node.exe' if os.name == 'nt' else 'node'
        first = self.write('first/' + executable, '#!/bin/sh\nexit 0\n')
        second = self.write('second/' + executable, '#!/bin/sh\nexit 0\n')
        first.chmod(0o755)
        second.chmod(0o755)
        bound = {'node': {'executable': str(first.resolve()), 'sha256': gate.digest(first)}}
        with patch.dict(os.environ, {'PATH': str(first.parent)}):
            gate.check_bridge_runtimes(bound)
            first.write_text('#!/bin/sh\nexit 1\n')
            with self.assertRaises(gate.Rejected):
                gate.check_bridge_runtimes(bound)
        with patch.dict(os.environ, {'PATH': str(second.parent)}):
            with self.assertRaises(gate.Rejected):
                gate.check_bridge_runtimes(bound)

    def test_unchecked_proof_escapes_rejected(self):
        for body in ('theorem bad : False := by sorry', 'axiom bad : False',
                     'theorem bad : True := by native_decide', 'run_elab pure ()',
                     'unsafe def bad := 0', 'set_option debug.skipKernelTC true',
                     'import Unregistered', '#eval IO.println "escape"',
                     'import\nLean\ntheorem accepted : True := True.intro',
                     'import /- split header -/\nLean', 'import\tLean',
                     'elab "escape" : tactic => pure ()', 'opaque bad : False'):
            with self.subTest(body=body), self.assertRaises(gate.Rejected):
                gate.check_proof_source(body, {'AlloyStudio.Example'})

    def test_imports_used_by_topological_build_match_scanned_imports(self):
        self.assertEqual(gate.check_proof_source('import Std\nimport AlloyStudio.One AlloyStudio.Two\n',
            {'AlloyStudio.One', 'AlloyStudio.Two'}), ['Std', 'AlloyStudio.One', 'AlloyStudio.Two'])

    def test_source_scanner_preserves_comments_and_does_not_reject_local_hypotheses(self):
        gate.check_proof_source('import Std\n/- sorry /- axiom -/ -/\n'
            'def text := "sorry \\" axiom"\n-- native_decide\n'
            'theorem identity (p : Prop) (h : p) : p := by assumption', set())

    def inventory(self):
        row = {'kind': 'theorem', 'name': 'AlloyStudio.Example.sound',
               'module': 'AlloyStudio.Example', 'levelParameters': [], 'axioms': [],
               'type': 'EXACT_ELABORATED_EXPRESSION'}
        expected = {row['name']: {k: row[k] for k in ('name', 'module', 'levelParameters')} |
                    {'typeSha256': hashlib.sha256(row['type'].encode()).hexdigest()}}
        return row, expected

    def test_sorry_custom_and_standard_axiom_dependencies_rejected(self):
        row, expected = self.inventory()
        for dependency in ('sorryAx', 'AlloyStudio.forgery', 'propext', 'Classical.choice', 'Quot.sound'):
            with self.subTest(dependency=dependency), self.assertRaises(gate.Rejected):
                gate.check_inventory([{**row, 'axioms': [dependency]}], expected)
        with self.assertRaises(gate.Rejected):
            gate.check_inventory([{'kind': 'forbidden-project-axiom', 'name': 'AlloyStudio.forgery'}], expected)

    def test_missing_extra_duplicate_or_changed_theorems_rejected(self):
        row, expected = self.inventory()
        gate.check_inventory([row], expected)
        for rows in ([], [row, row], [{**row, 'name': 'AlloyStudio.Example.other'}],
                     [{**row, 'type': 'WEAKER_STATEMENT'}], [{**row, 'levelParameters': ['extra']}],
                     [{**row, 'module': 'AlloyStudio.Other'}]):
            with self.subTest(rows=rows), self.assertRaises(gate.Rejected):
                gate.check_inventory(rows, expected)

    def test_malformed_valid_json_audit_has_a_blocking_diagnostic(self):
        row, expected = self.inventory()
        for malformed in ([[]], [None], [4], [True], [{**row, 'type': []}],
                          [{**row, 'levelParameters': 'bad'}], None):
            with self.subTest(malformed=malformed), self.assertRaises(gate.Rejected):
                gate.check_inventory(malformed, expected)

    def test_killed_compiler_is_infrastructure_failure(self):
        with patch.object(gate, 'isolated_command', return_value=['fake-lean']), \
                patch.object(gate.subprocess, 'run', return_value=subprocess.CompletedProcess(['fake-lean'], -9, b'')):
            with self.assertRaises(RuntimeError):
                gate.run(['fake-lean'], self.root, {}, self.root / 'killed.log')

    def reviews(self):
        manifest = self.write('formal/blocks/B01.json', {'id': 'B01'})
        prior = {}
        paths = []
        for tier, (prefix, model) in enumerate(gate.TIERS, 1):
            current = {}
            for suffix in ('a', 'b'):
                name = f'formal/reviews/B01/{prefix}-{suffix}.json'
                path = self.write(name, {'reviewerModel': model, 'tier': tier,
                    'blockManifestSha256': gate.digest(manifest), 'priorReviews': dict(prior),
                    'verdict': 'no_constructed_breach', 'findings': []})
                self.write(name.removesuffix('.json') + '.md', 'Independent review coverage fixture.\n')
                current[name] = gate.digest(path)
                paths.append(path)
            prior.update(current)
        return paths

    def test_six_correctly_ordered_reports_required(self):
        paths = self.reviews()
        self.assertEqual(len(gate.check_reviews(self.root, 'formal/blocks/B01.json')), 6)
        paths[-1].unlink()
        with self.assertRaises(gate.Rejected):
            gate.check_reviews(self.root, 'formal/blocks/B01.json')

    def test_wrong_model_stale_block_unseen_lower_tier_or_breach_rejected(self):
        for field, value in (('reviewerModel', 'wrong-model'), ('blockManifestSha256', 'stale'),
                             ('priorReviews', {}), ('verdict', 'constructed_breach'),
                             ('findings', [{'classification': 'breach', 'witness': None}])):
            paths = self.reviews()
            original = json.loads(paths[-1].read_text())
            self.write(paths[-1].relative_to(self.root), {**original, field: value})
            with self.subTest(field=field), self.assertRaises(gate.Rejected):
                gate.check_reviews(self.root, 'formal/blocks/B01.json')

    def test_modified_luna_report_invalidates_later_tiers(self):
        paths = self.reviews()
        paths[0].write_text(paths[0].read_text() + '\n')
        with self.assertRaises(gate.Rejected):
            gate.check_reviews(self.root, 'formal/blocks/B01.json')

    def test_missing_and_escaping_input_rejected(self):
        for relative in ('../escape', str(self.root / 'absolute'), 'missing'):
            with self.subTest(relative=relative), self.assertRaises(gate.Rejected):
                gate.inside(self.root, relative)

    def test_entrypoint_cannot_hide_uncompiled_declarations(self):
        self.write('formal/AlloyStudio/Example.lean', 'import Std\n')
        self.write('formal/AlloyStudio.lean', 'import AlloyStudio.Example\n')
        gate.check_barrel(self.root)
        self.write('formal/AlloyStudio.lean', 'import AlloyStudio.Example\ntheorem hidden : True := True.intro\n')
        with self.assertRaises(gate.Rejected):
            gate.check_barrel(self.root)

    def test_changed_source_cannot_inherit_block_verification(self):
        source = self.write('formal/AlloyStudio/Example.lean', 'import Std\n')
        block = {'schemaVersion': 1, 'id': 'B01', 'leanToolchain': 'leanprover/lean4:v4.34.1',
                 'flags': gate.FLAGS, 'allowlistedAxioms': [], 'doesNotCloseOriginalObligations': True,
                 'sources': {'formal/AlloyStudio/Example.lean': gate.digest(source)}, 'theorems': []}
        self.write('formal/blocks/B01.json', block)
        gate.load_blocks(self.root, ['B01'], block['leanToolchain'])
        source.write_text('import Std\ntheorem weaker : True := True.intro\n')
        with self.assertRaises(gate.Rejected):
            gate.load_blocks(self.root, ['B01'], block['leanToolchain'])


if __name__ == '__main__':
    unittest.main()
