"""Result acceptance for the bounded actual-HTTP functional comparison."""
from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ingress_invariance',ROOT/'scripts/check_ingress_functional_invariance.py')
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


def arms():
    return [dict(arm=name,rows=[dict(case='incorrect/canonical',kind='feedback',httpStatus=200,
        semanticSha256='a'*64,status='ok',expectedStatus='ok',errors=[])])
        for name in ('archived-oneshot','oneshot','persistent')]


class FunctionalComparisonTests(unittest.TestCase):
    def test_empty_json_not_modified_response_is_not_decoded(self):
        connection = Mock()
        response = connection.getresponse.return_value
        response.status = 304
        response.read.return_value = b''
        response.getheader.side_effect = lambda name, default=None: {
            'Content-Type': 'application/json', 'ETag': '"fixture"'
        }.get(name, default)
        with patch.object(harness, 'HTTPConnection', return_value=connection):
            result = harness.request(Mock(server_port=1234), 'GET', '/api/exercises')
        self.assertEqual(result['httpStatus'], 304)
        self.assertEqual(result['bodyBytes'], 0)
        self.assertIsNone(result['body'])
        connection.close.assert_called_once()

    def test_only_complete_matching_arms_pass(self):
        self.assertEqual(harness.compare_arms(arms())['status'],'PASS')
        self.assertEqual(harness.compare_arms(arms()[:2])['status'],'FAIL')

    def test_equal_timeouts_cannot_become_successful_functional_evidence(self):
        values=arms()
        for arm in values:arm['rows'][0]['status']='timeout'
        result=harness.compare_arms(values)
        self.assertEqual(result['status'],'FAIL')
        self.assertEqual({row['code'] for row in result['failures']},{'UNEXPECTED_STATUS'})

    def test_changed_semantic_digest_or_http_status_blocks(self):
        for field,value in [('semanticSha256','b'*64),('httpStatus',503)]:
            values=arms();values[-1]['rows'][0][field]=value
            self.assertEqual(harness.compare_arms(values)['status'],'FAIL')

    def test_missing_duplicate_reordered_and_invalid_cases_block(self):
        for variation in ('missing','duplicate','invalid'):
            values=arms()
            if variation=='missing':values[-1]['rows'].clear()
            elif variation=='duplicate':values[-1]['rows'].append(deepcopy(values[-1]['rows'][0]))
            else:values[-1]['rows'][0]['errors']=[dict(code='PARTIAL_POOL',path='$')]
            self.assertEqual(harness.compare_arms(values)['status'],'FAIL')

    def test_cases_include_both_metrics_diagnostics_unicode_and_behavior(self):
        record=dict(id='synthetic',starter='some Node')
        cases=harness.cases(record,('no Node','not some Node'))
        identifiers=[row[0] for row in cases]
        self.assertEqual(len(identifiers),13)
        for metric in ('canonical','ast'):
            for name in ('incorrect','correct','unicode','invalid','empty'):
                self.assertIn(name+'/'+metric,identifiers)
        for _,_,kind,payload,context in cases:
            if kind=='feedback':
                self.assertEqual(context['poolSize'],2)
                self.assertEqual(context['body'],payload['body'])


if __name__=='__main__':
    unittest.main()
