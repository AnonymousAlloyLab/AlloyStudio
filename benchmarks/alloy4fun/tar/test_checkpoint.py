"""Crash-durable TAR evidence checkpoints; no Java or corpus access required."""
import gzip
import hashlib
import json
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import unittest
import zlib

from benchmarks.alloy4fun import run_tar


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        base = run_tar.ROOT / 'build/tests/tmp'
        base.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix='tar-checkpoint-', dir=base)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.manifest = 'a' * 64
        self.cases = [dict(case_id=f'fixture/over/case{index}_inv1.als',
                           group='fixture', predicate='inv1', cohort_status='OVERCONSTRAINED',
                           source_sha256=str(index + 1) * 64) for index in range(2)]
        self.rows = [{**case, 'tool': 'tar-depth-2', 'status': 'no_repair',
                      'wall_seconds': .01, 'hint_available': False} for case in self.cases]
        self.response = {'status': 'no_repair', 'hint_available': False, 'wall_s': .01}

    def append(self, index=0):
        with (self.root / 'responses.jsonl.gz').open('ab') as raw, \
                (self.root / 'results.jsonl').open('ab') as rows:
            run_tar.append_checkpoint(raw, rows, self.rows[index], self.response, self.manifest)

    def validate(self, cases=None, manifest=None):
        return run_tar.validate_checkpoint(self.root, cases or self.cases,
                                           manifest or self.manifest, 'tar-depth-2')

    def evidence_hashes(self):
        return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in self.root.iterdir() if path.is_file()}

    def assert_refused_without_mutation(self, **options):
        before = self.evidence_hashes()
        with self.assertRaisesRegex(ValueError, 'Checkpoint refused'):
            self.validate(**options)
        self.assertEqual(self.evidence_hashes(), before)

    def test_fresh_and_complete_checkpoints_resume_with_one_member_per_response(self):
        self.assertEqual(self.validate(), [])
        self.append()
        self.assertEqual(self.validate(), self.rows[:1])
        self.append(1)
        self.assertEqual(self.validate(), self.rows)
        compressed = (self.root / 'responses.jsonl.gz').read_bytes()
        members = 0
        while compressed:
            decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
            payload = decoder.decompress(compressed)
            self.assertTrue(decoder.eof)
            self.assertEqual(len(payload.splitlines()), 1)
            compressed = decoder.unused_data
            members += 1
        self.assertEqual(members, 2)
        with gzip.open(self.root / 'responses.jsonl.gz', 'rt') as stream:
            self.assertEqual([json.loads(line)['case_id'] for line in stream],
                             [case['case_id'] for case in self.cases])

    def test_resume_rejects_source_manifest_and_unknown_case_provenance(self):
        self.append()
        self.assert_refused_without_mutation(manifest='b' * 64)
        for field in ('source_sha256', 'group', 'predicate', 'cohort_status'):
            cases = [dict(self.cases[0], **{field: 'different'}), self.cases[1]]
            with self.subTest(field=field):
                self.assert_refused_without_mutation(cases=cases)
        self.assert_refused_without_mutation(cases=[self.cases[1]])

    def test_resume_rejects_duplicate_and_changed_numeric_rows(self):
        self.append()
        original = (self.root / 'results.jsonl').read_bytes()
        (self.root / 'results.jsonl').write_bytes(original * 2)
        self.assert_refused_without_mutation()
        changed = {**self.rows[0], 'wall_seconds': .02}
        (self.root / 'results.jsonl').write_bytes(run_tar.checkpoint_bytes(changed) + b'\n')
        self.assert_refused_without_mutation()

    def test_resume_rejects_partial_numeric_row_and_missing_partner(self):
        self.append()
        path = self.root / 'results.jsonl'
        path.write_bytes(path.read_bytes()[:-1])
        self.assert_refused_without_mutation()
        path.unlink()
        self.assert_refused_without_mutation()

    def test_resume_rejects_corrupt_gzip_trailer_even_after_complete_record(self):
        self.append()
        path = self.root / 'responses.jsonl.gz'
        data = bytearray(path.read_bytes())
        data[-8] ^= 1  # Alter CRC32 without damaging the readable JSON record.
        path.write_bytes(data)
        self.assert_refused_without_mutation()

    def test_resume_rejects_unmatched_complete_raw_member(self):
        self.append()
        with (self.root / 'responses.jsonl.gz').open('ab') as stream:
            stream.write(gzip.compress(b'{"case_id":"uncommitted"}\n', mtime=0))
        self.assert_refused_without_mutation()

    def kill_writer(self, point):
        """Kill a real Python process at a deterministic file-commit boundary."""
        child = '''
import gzip,json,os,sys
from pathlib import Path
from benchmarks.alloy4fun import run_tar
directory=Path(sys.argv[1]); point=sys.argv[2]
row=json.loads(sys.argv[3]); response=json.loads(sys.argv[4]); manifest=sys.argv[5]
def stop():
    print('ready',flush=True)
    sys.stdin.buffer.read(1)
with (directory/'responses.jsonl.gz').open('ab') as raw, (directory/'results.jsonl').open('ab') as rows:
    if point=='partial_raw':
        member=gzip.compress(run_tar.checkpoint_bytes({'case_id':row['case_id'],'response':response})+b'\\n',mtime=0)
        raw.write(member[:len(member)//2]); raw.flush(); os.fsync(raw.fileno()); stop()
    else:
        original=run_tar.sync_file
        if point=='between_raw_and_row':
            def synced(stream):
                original(stream)
                if stream is raw: stop()
            run_tar.sync_file=synced
        run_tar.append_checkpoint(raw,rows,row,response,manifest)
        stop()
'''
        process = subprocess.Popen([sys.executable, '-c', child, str(self.root), point,
                                    json.dumps(self.rows[0]), json.dumps(self.response), self.manifest],
                                   cwd=run_tar.ROOT, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                self.assertTrue(selector.select(10), 'writer did not reach checkpoint boundary')
            self.assertEqual(process.stdout.readline(), b'ready\n')
        finally:
            process.kill()
            _, stderr = process.communicate(timeout=10)
        self.assertFalse(stderr, stderr.decode(errors='replace'))
        self.assertNotEqual(process.returncode, 0)

    def test_process_kill_between_raw_and_row_refuses_preserved_evidence(self):
        self.kill_writer('between_raw_and_row')
        self.assertEqual((self.root / 'results.jsonl').read_bytes(), b'')
        self.assertTrue(gzip.decompress((self.root / 'responses.jsonl.gz').read_bytes()).endswith(b'\n'))
        self.assert_refused_without_mutation()

    def test_process_kill_during_raw_member_refuses_preserved_evidence(self):
        self.kill_writer('partial_raw')
        self.assert_refused_without_mutation()

    def test_process_kill_after_durable_pair_preserves_resumable_evidence(self):
        self.kill_writer('after_pair')
        self.assertEqual(self.validate(), self.rows[:1])
        self.append(1)
        self.assertEqual(self.validate(), self.rows)


if __name__ == '__main__':
    unittest.main()
