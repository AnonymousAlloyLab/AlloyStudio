"""Run the IIS task entry point on this host; no Windows scheduler is simulated."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / 'deploy/iis/run_backend.py'


class IISLauncherTests(unittest.TestCase):
    def configure(self, folder, source, *, enable_luna):
        backend = folder / 'private backend'
        logs = folder / 'private logs'
        secrets = folder / 'private secrets'
        for directory in (backend, logs, secrets):
            directory.mkdir()
        (backend / 'server.py').write_text(source, encoding='utf-8')
        (backend / 'fixture_module.py').write_text('VALUE = "probe"\n', encoding='utf-8')
        key_file = secrets / 'openai.key'
        key_file.write_text('SYNTHETIC_CONFIGURED_KEY\n', encoding='utf-8')
        config = {'backend_root': str(backend), 'log_directory': str(logs),
                  'key_file': str(key_file), 'enable_luna': enable_luna,
                  'java_exe': str(folder / 'Java runtime/bin/java.exe'),
                  'engine_timeout': 12, 'workers': 4,
                  'public_origins': ['https://alloy.example', 'https://alias.example:8443']}
        config_path = folder / 'backend-task.json'
        config_path.write_text(json.dumps(config), encoding='utf-8-sig')
        return config_path, config

    def launch(self, config_path):
        # All credential variables supplied to the child are synthetic. The
        # probe records presence only and never reads the configured key file.
        env = dict(os.environ, OPENAI_API_KEY='SYNTHETIC_INHERITED_KEY',
                   OPENAI_CONFIG_FILE='SYNTHETIC_WRONG_CONFIG',
                   OPENAI_API_KEY_FILE='SYNTHETIC_WRONG_FILE', OPENAI_DISABLED='inherited',
                   PYTHONDONTWRITEBYTECODE='0', PYTHONIOENCODING='ascii')
        process = subprocess.Popen([sys.executable, str(LAUNCHER), '--config', str(config_path)],
                                   cwd=config_path.parent, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, encoding='utf-8')
        try:
            stdout, stderr = process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            self.fail('Task launcher did not terminate')
        return process, stdout, stderr

    def test_backend_runs_in_task_process_with_private_configuration(self):
        source = '''import fixture_module
import json
import os
from pathlib import Path
import sys
probe = {'pid': os.getpid(), 'argv': sys.argv, 'cwd': os.getcwd(),
         'path': sys.path[0], 'key_present': 'OPENAI_API_KEY' in os.environ,
         'key_file': os.environ.get('OPENAI_API_KEY_FILE'),
         'config_file': os.environ.get('OPENAI_CONFIG_FILE'),
         'disabled': os.environ.get('OPENAI_DISABLED'),
         'no_bytecode': sys.dont_write_bytecode,
         'io_encoding': os.environ.get('PYTHONIOENCODING')}
Path('probe.json').write_text(json.dumps(probe), encoding='utf-8')
print('Canonical feedback: λ → ∀ “no”', flush=True)
'''
        for enable_luna in (False, True):
            with self.subTest(enable_luna=enable_luna), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory).resolve()
                config_path, config = self.configure(folder, source, enable_luna=enable_luna)
                process, stdout, stderr = self.launch(config_path)
                self.assertEqual(process.returncode, 0, stderr)
                self.assertEqual((stdout, stderr), ('', ''))
                backend = Path(config['backend_root'])
                probe = json.loads((backend / 'probe.json').read_text(encoding='utf-8'))
                self.assertEqual(probe['pid'], process.pid, 'Task must own the backend process directly')
                self.assertEqual(probe['argv'], [str(backend / 'server.py'), '--host', '127.0.0.1',
                    '--port', '8080', '--java', config['java_exe'], '--timeout', '12', '--workers', '4',
                    '--public-origin', 'https://alloy.example', '--public-origin', 'https://alias.example:8443'])
                self.assertEqual(probe['cwd'], str(backend))
                self.assertEqual(probe['path'], str(backend))
                self.assertFalse(probe['key_present'])
                self.assertEqual(probe['key_file'], config['key_file'])
                self.assertIsNone(probe['config_file'], 'Inherited config must not select another deployment credential')
                self.assertEqual(probe['disabled'], '0' if enable_luna else '1')
                self.assertTrue(probe['no_bytecode'])
                self.assertEqual(probe['io_encoding'], 'utf-8')
                self.assertFalse(list(backend.rglob('*.pyc')))
                log = (Path(config['log_directory']) / 'backend.log').read_text(encoding='utf-8')
                self.assertEqual(log, 'Canonical feedback: λ → ∀ “no”\n')
                self.assertNotIn('SYNTHETIC_INHERITED_KEY', log)

    def test_deployment_json_config_selected_without_inherited_credential(self):
        source = '''import json
import os
from pathlib import Path
probe = {'config_file': os.environ.get('OPENAI_CONFIG_FILE'),
         'key_file': os.environ.get('OPENAI_API_KEY_FILE'),
         'key_present': 'OPENAI_API_KEY' in os.environ,
         'disabled': os.environ.get('OPENAI_DISABLED')}
Path('probe.json').write_text(json.dumps(probe), encoding='utf-8')
'''
        for enable_luna in (False, True):
            with self.subTest(enable_luna=enable_luna), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory).resolve()
                config_path, config = self.configure(folder, source, enable_luna=enable_luna)
                backend = Path(config['backend_root'])
                local_config = backend / 'openai.local.json'
                local_config.write_text(json.dumps({'api_key': 'SYNTHETIC_LOCAL_CONFIG_KEY'}), encoding='utf-8')
                process, stdout, stderr = self.launch(config_path)
                self.assertEqual(process.returncode, 0, stderr)
                self.assertEqual((stdout, stderr), ('', ''))
                probe = json.loads((backend / 'probe.json').read_text(encoding='utf-8'))
                self.assertEqual(probe['config_file'], str(local_config))
                self.assertEqual(probe['key_file'], config['key_file'])
                self.assertFalse(probe['key_present'])
                self.assertEqual(probe['disabled'], '0' if enable_luna else '1')
                log = (Path(config['log_directory']) / 'backend.log').read_text(encoding='utf-8')
                self.assertNotIn('SYNTHETIC_LOCAL_CONFIG_KEY', log)
                self.assertNotIn('SYNTHETIC_INHERITED_KEY', log)

    def test_private_runtime_exception_is_sanitized_and_fails_for_restart(self):
        for exception in ('RuntimeError', 'SystemExit'):
            with self.subTest(exception=exception), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory).resolve()
                config_path, config = self.configure(folder,
                    f"raise {exception}('PRIVATE_EXCEPTION_CANARY')\n", enable_luna=False)
                process, stdout, stderr = self.launch(config_path)
                self.assertEqual(process.returncode, 1)
                log = (Path(config['log_directory']) / 'backend.log').read_text(encoding='utf-8')
                self.assertIn('Backend ', log)
                for output in (stdout, stderr, log):
                    self.assertNotIn('PRIVATE_EXCEPTION_CANARY', output)
                    self.assertNotIn('Traceback', output)
                    self.assertNotIn('SYNTHETIC_INHERITED_KEY', output)


if __name__ == '__main__':
    unittest.main()
