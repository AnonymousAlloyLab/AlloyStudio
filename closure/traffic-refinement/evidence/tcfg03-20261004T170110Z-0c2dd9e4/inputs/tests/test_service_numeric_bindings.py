"""Constructed mutations of value-bearing service profile/source mappings."""
import ast
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.util.spec_from_file_location('service_numeric_bindings', ROOT/'scripts/service_numeric_bindings.py')
bindings = importlib.util.module_from_spec(loader)
loader.loader.exec_module(bindings)


class ServiceNumericBindingsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = json.loads((ROOT/'closure/traffic-refinement/service-profile.json').read_text())

    def profile_row(self, identifier):
        profile = deepcopy(self.profile)
        profile['limits'] = [r for r in profile['limits'] if r['id']==identifier]
        return profile, profile['limits'][0]

    def test_complete_frozen_source_values_are_extracted(self):
        result = bindings.check(ROOT,self.profile)
        self.assertEqual(result['status'],'PASS')
        self.assertEqual(result['limits'],len(self.profile['limits']))
        self.assertEqual(result['declarationOnly'],16)
        self.assertEqual(result['explicitlyDisabled'],2)
        values = bindings.extracted_values(ROOT,self.profile)
        self.assertEqual(values,{r['id']:r['value'] for r in self.profile['limits']})

    def test_wrong_profile_value_and_numeric_type_block(self):
        for value in (6,5.0,True):
            profile,row=self.profile_row('admin.login_failures');row['value']=value
            with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_missing_binding_and_unknown_kind_block(self):
        profile,row=self.profile_row('http.public_handlers');row.pop('sourceBinding')
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)
        row['sourceBinding']={'kind':'trust-me','value':30}
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_removed_or_detached_ast_path_blocks(self):
        profile,row=self.profile_row('admin.login_failures')
        row['sourceBinding']['sourcePath']=['body',999,'value']
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)
        profile,row=self.profile_row('admin.login_failures');row['sourceBinding'].pop('expressionPath')
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_expression_changed_without_updating_source_blocks(self):
        profile,row=self.profile_row('admin.login_failures')
        row['sourceBinding']['expression']='len(self._failures) >= 6'
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_named_scope_and_source_paths_must_resolve(self):
        profile,row=self.profile_row('admin.login_failures');row['sourceBinding']['scope']='Other.login'
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)
        profile,row=self.profile_row('admin.login_failures');row['source']='../admin_auth.py'
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_closed_arithmetic_never_executes_code(self):
        for text in ('some_name','True',"__import__('os').system('false')",'float("nan")','1/0','2**65','(2**64)**64'):
            with self.subTest(text=text),self.assertRaises(bindings.BindingError):
                bindings._number(ast.parse(text,mode='eval').body)
        self.assertEqual(bindings._number(ast.parse('16 * 1048576',mode='eval').body),16777216)

    def test_multisite_write_drift_is_detected_with_constructed_source(self):
        profile,row=self.profile_row('worker.pipe_chunk')
        scratch=ROOT/'build/trf-closure/numeric-binding-tests';scratch.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='chunk-',dir=scratch) as directory:
            root=Path(directory);source=(ROOT/row['source']).read_text()
            self.assertEqual(source.count('view[:65536]'),1)
            (root/row['source']).write_text(source.replace('view[:65536]','view[:32768]'))
            with self.assertRaises(bindings.BindingError):bindings.check(root,profile)

    def test_text_literal_ambiguity_and_lossy_conversion_block(self):
        profile,row=self.profile_row('worker.heap');row['sourceBinding']['text']='m'
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)
        with self.assertRaises(bindings.BindingError):
            bindings._text('number=1.5;',dict(kind='text-numeric',text='number=1.5;',numeric='1.5',
                scale=1,subtract=0,conversion='integer-exact'))

    def test_linked_dependency_cycle_or_unknown_reference_blocks(self):
        profile=deepcopy(self.profile)
        row=next(r for r in profile['limits'] if r['id']=='behavior.bitwidth')
        for name in ('behavior.bitwidth','missing.limit'):
            row['sourceBinding']['reference']=name
            with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_external_targets_cannot_self_attest_source_values(self):
        profile,row=self.profile_row('http.public_handlers')
        row['sourceBinding']={'kind':'declaration','justification':'Claimed external'}
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_disabled_reference_cache_is_an_explicit_profile_setting(self):
        profile,row=self.profile_row('prepared.entries')
        self.assertEqual(bindings.check(ROOT,profile)['explicitlyDisabled'],1)
        profile['selected']['preparedPoolCaching']=True
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_discard_loop_cannot_be_claimed_for_other_zero_limits(self):
        profile,row=self.profile_row('worker.stderr_retained');row['id']='another.zero'
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)

    def test_duplicate_limit_ids_block(self):
        profile,row=self.profile_row('admin.login_failures');profile['limits'].append(deepcopy(row))
        with self.assertRaises(bindings.BindingError):bindings.check(ROOT,profile)


if __name__=='__main__':
    unittest.main()
