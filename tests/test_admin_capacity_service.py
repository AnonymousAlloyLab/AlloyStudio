"""Constructed service witnesses for discovery authority and retained-byte bounds."""
from contextlib import contextmanager
from copy import deepcopy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from admin_auth import AuthError
import admin_service as service
from admin_upload import MAX_PREPARED_BYTES, UploadError


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/upload-capacity-validation'
ENVELOPE = dict(source='sig Node {} pred inv1C0 {some Node}',
                filename='selection.als', modelId='selection')
PREPARED = dict(documents=[dict(id='selection-inv1', predicate='inv1', title='inv1',
                               predicateHeader='pred inv1 ',
                               description='Require a Node.', oracleSolutions=['some Node'])],
                certificates=[], witness=dict(filename='selection.als', sourceSha256='a' * 64,
                    originalSource=ENVELOPE['source'], groups=[dict(variants=[dict(name='inv1C0')])]))
DISCOVERED = dict(filename='selection.als', sourceSha256='a' * 64, equivalenceScope=5,
                 groups=[dict(id=f'selection-inv{i}', predicate=f'inv{i}', title=f'inv{i}',
                              question='Review the requirement.', oracleCount=2,
                              variants=[f'inv{i}C0', f'inv{i}C1']) for i in range(1, 101)])


class QueuedThread:
    pending = []

    def __init__(self, *, target, args, daemon):
        self.target, self.args = target, args

    def start(self):
        self.pending.append(self)

    @classmethod
    def run(cls):
        item = cls.pending.pop(0)
        item.target(*item.args)


class FixtureAuth:
    def __init__(self):
        self.revoked = set()

    def validate(self, principal):
        if principal.owner in self.revoked:
            raise AuthError(401, 'Session expired.')

    @contextmanager
    def guard(self, principal):
        self.validate(principal)
        yield


class AdminCapacityServiceTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='service-', dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.now = [1000.0]
        self.auth = FixtureAuth()
        self.portal = SimpleNamespace(root=self.root, java='java', snapshot='original')
        self.service = service.AdminService(self.portal, self.auth, clock=lambda: self.now[0])
        self.principal = self.owner(0)
        QueuedThread.pending = []
        patcher = patch.object(service.threading, 'Thread', QueuedThread)
        patcher.start()
        self.addCleanup(patcher.stop)

    @staticmethod
    def owner(index):
        return SimpleNamespace(owner=f'owner-{index}', generation='test-generation')

    def ready(self, owner=None, *, byte_size=None):
        owner = owner or self.principal
        pending = self.service.prepare(owner, dict(ENVELOPE, practicePredicates=['inv1']))
        with patch.object(service, 'prepare_upload', return_value=deepcopy(PREPARED)) as prepare:
            if byte_size is None:
                QueuedThread.run()
            else:
                with patch.object(service, 'prepared_size', return_value=byte_size):
                    QueuedThread.run()
        self.assertEqual(prepare.call_args.kwargs['timeout'], 60)
        return self.service.view(owner, pending['id'])

    def denied(self, status, operation):
        with self.assertRaises(service.AdminError) as raised:
            operation()
        self.assertEqual(raised.exception.status, status)

    def test_discovery_has_no_publication_or_provider_authority(self):
        pending = self.service.prepare(self.principal, dict(ENVELOPE, practicePredicates=[]))
        with (patch.object(service, 'discover_upload', return_value=deepcopy(DISCOVERED)) as discover,
              patch.object(service, 'prepare_upload') as prepare):
            QueuedThread.run()
        discover.assert_called_once()
        self.assertEqual(discover.call_args.kwargs['timeout'], 60)
        prepare.assert_not_called()
        view = self.service.view(self.principal, pending['id'])
        self.assertEqual(view['state'], 'selecting')
        self.assertEqual(len(view['groups']), 100)
        draft = self.service.drafts[pending['id']]
        self.assertIsNone(draft.prepared)
        self.assertEqual(draft.prepared_bytes, 0)
        with patch.object(service, 'commit_upload') as commit, patch.object(service, 'suggest') as suggest:
            self.denied(409, lambda: self.service.commit(self.principal, view['id'], view['revision'], []))
            self.denied(409, lambda: self.service.request_suggestion(
                self.principal, view['id'], view['revision'], ''))
        commit.assert_not_called()
        suggest.assert_not_called()
        view['groups'][0]['variants'].append('tampered')
        self.assertEqual(self.service.view(self.principal, pending['id'])['groups'][0]['variants'],
                         ['inv1C0', 'inv1C1'])

    def test_global_serialized_budget_exact_boundary_then_discards_release_it(self):
        views = [self.ready(self.owner(index), byte_size=MAX_PREPARED_BYTES) for index in range(4)]
        self.assertTrue(all(view['state'] == 'ready' for view in views))
        self.assertEqual(sum(item.prepared_bytes for item in self.service.drafts.values()),
                         service.MAX_RETAINED_PREPARED_BYTES)
        rejected = self.ready(self.owner(4), byte_size=1)
        self.assertEqual(rejected['state'], 'rejected')
        self.assertIsNone(self.service.drafts[rejected['id']].prepared)
        self.assertEqual(self.service.drafts[rejected['id']].prepared_bytes, 0)
        self.service.discard(self.owner(0), views[0]['id'])
        retry = self.ready(self.owner(5), byte_size=MAX_PREPARED_BYTES)
        self.assertEqual(retry['state'], 'ready')
        self.assertEqual(sum(item.prepared_bytes for item in self.service.drafts.values()),
                         service.MAX_RETAINED_PREPARED_BYTES)

    def test_oversized_prepared_data_is_not_retained_and_releases_worker(self):
        pending = self.service.prepare(self.principal, ENVELOPE)
        oversized = deepcopy(PREPARED)
        oversized['witness']['originalSource'] = 'x' * MAX_PREPARED_BYTES
        with patch.object(service, 'prepare_upload', return_value=oversized):
            QueuedThread.run()
        draft = self.service.drafts[pending['id']]
        self.assertEqual(draft.state, 'rejected')
        self.assertIsNone(draft.prepared)
        self.assertEqual(draft.prepared_bytes, 0)
        self.assertEqual(self.ready()['state'], 'ready')

    def test_one_worker_covers_discovery_preparation_and_provider(self):
        waiting = self.service.prepare(self.principal, dict(ENVELOPE, practicePredicates=[]))
        self.denied(429, lambda: self.service.prepare(self.owner(1), ENVELOPE))
        with patch.object(service, 'discover_upload', side_effect=UploadError('Discovery timed out.')):
            QueuedThread.run()
        self.assertEqual(self.service.view(self.principal, waiting['id'])['state'], 'rejected')
        ready = self.ready(self.owner(1))
        self.service.request_suggestion(self.owner(1), ready['id'], ready['revision'], '')
        self.denied(429, lambda: self.service.prepare(self.owner(2), ENVELOPE))
        with patch.object(service, 'suggest', side_effect=RuntimeError('private provider detail')):
            QueuedThread.run()
        self.assertEqual(self.service.view(self.owner(1), ready['id'])['state'], 'ready')
        self.assertEqual(self.ready(self.owner(2))['state'], 'ready')

    def test_selection_types_are_rejected_before_queued_worker(self):
        for selection in (None, True, 'inv1', ['inv1', 'inv1'], [False], ['a'] * 101):
            with self.subTest(selection=selection), patch.object(service, 'prepare_upload') as prepare:
                self.denied(400, lambda: self.service.prepare(
                    self.principal, dict(ENVELOPE, practicePredicates=selection)))
                prepare.assert_not_called()
        self.assertEqual(QueuedThread.pending, [])
        self.assertEqual(self.service.drafts, {})

    def test_expiry_and_revocation_release_retained_capacity_and_late_discovery(self):
        for index in range(4):
            self.ready(self.owner(index), byte_size=MAX_PREPARED_BYTES)
        self.now[0] += service.DRAFT_SECONDS
        self.assertEqual(self.ready(self.owner(5), byte_size=MAX_PREPARED_BYTES)['state'], 'ready')
        self.assertEqual(len(self.service.drafts), 1)
        self.auth.revoked.add('owner-5')
        self.assertEqual(self.ready(self.owner(6), byte_size=MAX_PREPARED_BYTES)['state'], 'ready')
        self.assertEqual(len(self.service.drafts), 1)
        pending = self.service.prepare(self.owner(7), dict(ENVELOPE, practicePredicates=[]))

        def discover(*args, **kwargs):
            self.auth.revoked.add('owner-7')
            return deepcopy(DISCOVERED)

        with patch.object(service, 'discover_upload', side_effect=discover):
            QueuedThread.run()
        self.assertNotIn(pending['id'], self.service.drafts)
        self.assertTrue(self.service.idle.is_set())

    def test_commit_resets_retained_byte_accounting(self):
        ready = self.ready(byte_size=MAX_PREPARED_BYTES)

        def commit(root, prepared, metadata, *, guard):
            with guard():
                return SimpleNamespace(exercise_count=2)

        with patch.object(service, 'commit_upload', side_effect=commit):
            self.service.commit(self.principal, ready['id'], ready['revision'], [])
        draft = self.service.drafts[ready['id']]
        self.assertEqual(draft.state, 'committed')
        self.assertIsNone(draft.prepared)
        self.assertEqual(draft.prepared_bytes, 0)


if __name__ == '__main__':
    unittest.main()
