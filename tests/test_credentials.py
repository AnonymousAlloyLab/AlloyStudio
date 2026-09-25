"""Portable deployment-credential witnesses using temporary synthetic keys only.

No test reads a real credential or calls OpenAI. Relocation subprocesses print
only hashes and paths, never the synthetic credential contents.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import threading

import luna
import server
from scripts.verify_closure import inventory


ROOT = Path(__file__).resolve().parents[1]
KEY_A = "sk-" + "synthetic_A_" * 3
KEY_B = "sk-" + "synthetic_B_" * 3


class PortableCredentialTests(unittest.TestCase):
    def key_file(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\xef\xbb\xbf" + value.encode())
        path.chmod(0o600)

    def backend(self, directory):
        backend = directory / "portable backend with spaces"
        backend.mkdir(parents=True)
        shutil.copy2(ROOT / "luna.py", backend / "luna.py")
        return backend

    def observe(self, backend, cwd, home, variables=None, platform_override=""):
        environment = {key: value for key, value in os.environ.items()
                       if key in {"PATH", "LANG", "SYSTEMROOT", "WINDIR", "TMP", "TEMP"}}
        environment.update(HOME=str(home), USERPROFILE=str(home), PYTHONDONTWRITEBYTECODE="1")
        environment.update(variables or {})
        script = ("import hashlib,json,sys;sys.path.insert(0,sys.argv[1]);import luna;"
                  "setattr(luna.sys,'platform',sys.argv[2]) if sys.argv[2] else None;"
                  "print(json.dumps({'keyHash':hashlib.sha256(luna.read_key().encode()).hexdigest(),"
                  "'backendRoot':str(luna.BACKEND_ROOT)}))")
        result = subprocess.run([sys.executable, "-c", script, str(backend), platform_override], cwd=cwd,
                                env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, "Portable credential subprocess failed")
        self.assertEqual(result.stderr, "", "Credential loading must not log private configuration")
        self.assertNotIn(KEY_A, result.stdout + result.stderr)
        self.assertNotIn(KEY_B, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def config_file(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\xef\xbb\xbf" + json.dumps(value).encode())
        path.chmod(0o600)

    def test_default_private_file_survives_relocation_and_unrelated_home_and_cwd(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.key_file(backend / "secrets/openai.key", KEY_A)
            home = directory / "unrelated home"
            self.key_file(home / ".config/live-programming/openai.key", KEY_B)
            cwd = directory / "unrelated cwd"
            cwd.mkdir()
            first = self.observe(backend, cwd, home)
            relocated = directory / "Moved installation with spaces"
            shutil.move(str(backend), relocated)
            second = self.observe(relocated, cwd, home)
            expected = hashlib.sha256(KEY_A.encode()).hexdigest()
            self.assertEqual(first["keyHash"], expected)
            self.assertEqual(second["keyHash"], expected)
            self.assertEqual(Path(first["backendRoot"]), backend)
            self.assertEqual(Path(second["backendRoot"]), relocated)

    def test_relative_explicit_file_resolves_from_backend_not_working_directory(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            cwd = directory / "different cwd"
            cwd.mkdir()
            self.key_file(backend / "settings/personal.key", KEY_A)
            self.key_file(cwd / "settings/personal.key", KEY_B)
            observed = self.observe(backend, cwd, directory / "different home",
                                    {"OPENAI_API_KEY_FILE": "settings/personal.key"})
            self.assertEqual(observed["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())

    def test_implicit_home_key_is_never_loaded(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            home = directory / "other home"
            self.key_file(home / ".config/live-programming/openai.key", KEY_B)
            observed = self.observe(backend, directory, home)
            self.assertEqual(observed["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_environment_key_precedence_and_deployment_disable(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.key_file(backend / "secrets/openai.key", KEY_B)
            observed = self.observe(backend, directory, directory / "home", {"OPENAI_API_KEY": KEY_A})
            self.assertEqual(observed["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())
            disabled = self.observe(backend, directory, directory / "home",
                                    {"OPENAI_API_KEY": KEY_A, "OPENAI_DISABLED": "1"})
            self.assertEqual(disabled["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_default_json_config_direct_key_survives_relocation(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.config_file(backend / "openai.local.json", {"api_key": KEY_A})
            self.key_file(backend / "secrets/openai.key", KEY_B)
            first = self.observe(backend, directory, directory / "other home")
            relocated = directory / "Relocated JSON configuration with spaces"
            shutil.move(str(backend), relocated)
            second = self.observe(relocated, directory, directory / "different home")
            self.assertEqual(first["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())
            self.assertEqual(second["keyHash"], first["keyHash"])

    def test_json_file_pointer_resolves_from_config_directory(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.config_file(backend / "config/account.json", {"api_key_file": "credentials/account.key"})
            self.key_file(backend / "config/credentials/account.key", KEY_A)
            self.key_file(backend / "credentials/account.key", KEY_B)
            self.key_file(directory / "credentials/account.key", KEY_B)
            observed = self.observe(backend, directory, directory / "home",
                                    {"OPENAI_CONFIG_FILE": "config/account.json"})
            self.assertEqual(observed["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())

    def test_explicit_absolute_json_config_and_environment_precedence(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            config = directory / "private settings outside backend/account.json"
            self.config_file(config, {"api_key": "  " + KEY_A + "\n"})
            self.key_file(backend / "secrets/openai.key", KEY_B)
            variables = {"OPENAI_CONFIG_FILE": str(config), "OPENAI_API_KEY_FILE": "secrets/openai.key"}
            chosen = self.observe(backend, directory, directory / "home", variables)
            self.assertEqual(chosen["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())
            variables["OPENAI_API_KEY"] = KEY_B
            overridden = self.observe(backend, directory, directory / "home", variables)
            self.assertEqual(overridden["keyHash"], hashlib.sha256(KEY_B.encode()).hexdigest())
            variables["OPENAI_DISABLED"] = "1"
            disabled = self.observe(backend, directory, directory / "home", variables)
            self.assertEqual(disabled["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_explicit_plain_file_takes_precedence_over_default_json(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.config_file(backend / "openai.local.json", {"api_key": KEY_B})
            self.key_file(backend / "settings/chosen.key", KEY_A)
            observed = self.observe(backend, directory, directory / "home",
                                    {"OPENAI_API_KEY_FILE": "settings/chosen.key"})
            self.assertEqual(observed["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())

    def test_missing_explicit_config_never_falls_back(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.config_file(backend / "openai.local.json", {"api_key": KEY_A})
            self.key_file(backend / "secrets/openai.key", KEY_B)
            observed = self.observe(backend, directory, directory / "home",
                                    {"OPENAI_CONFIG_FILE": "missing.json", "OPENAI_API_KEY_FILE": "secrets/openai.key"})
            self.assertEqual(observed["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_malformed_unknown_ambiguous_or_oversized_json_never_falls_back(self):
        fixtures = [b"{", b"[]", b"null", b"{}", b"\xff", b'{"api_key":"one","api_key":"two"}',
                    json.dumps({"unknown": KEY_A}).encode(),
                    json.dumps({"api_key": KEY_A, "api_key_file": "secrets/openai.key"}).encode(),
                    json.dumps({"api_key": 42}).encode(), json.dumps({"api_key_file": []}).encode(),
                    json.dumps({"api_key": ""}).encode(),
                    json.dumps({"api_key": "\ud800"}).encode(),
                    json.dumps({"api_key": KEY_A + "\r\n" + KEY_B}).encode(),
                    json.dumps({"api_key": KEY_A + " " + KEY_B}).encode(),
                    json.dumps({"api_key_file": "missing.key"}).encode(),
                    json.dumps({"api_key": "x" * 16384}).encode()]
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.key_file(backend / "secrets/openai.key", KEY_B)
            path = backend / "openai.local.json"
            for index, content in enumerate(fixtures):
                path.write_bytes(content)
                path.chmod(0o600)
                with self.subTest(fixture=index):
                    result = self.observe(backend, directory, directory / "home")
                    self.assertEqual(result["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_nonprivate_json_and_referenced_plain_files_are_rejected_on_posix(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            config = backend / "openai.local.json"
            self.config_file(config, {"api_key": KEY_A})
            config.chmod(0o644)
            rejected = self.observe(backend, directory, directory / "home", platform_override="linux")
            self.assertEqual(rejected["keyHash"], hashlib.sha256(b"").hexdigest())
            self.config_file(config, {"api_key_file": "secrets/openai.key"})
            plain = backend / "secrets/openai.key"
            self.key_file(plain, KEY_A)
            plain.chmod(0o644)
            rejected = self.observe(backend, directory, directory / "home", platform_override="linux")
            self.assertEqual(rejected["keyHash"], hashlib.sha256(b"").hexdigest())

    def test_windows_mode_bits_are_not_treated_as_ntfs_acl_evidence(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            config = backend / "openai.local.json"
            self.config_file(config, {"api_key": KEY_A})
            config.chmod(0o644)
            accepted = self.observe(backend, directory, directory / "home", platform_override="win32")
            self.assertEqual(accepted["keyHash"], hashlib.sha256(KEY_A.encode()).hexdigest())

    def test_oversized_explicit_plain_file_never_falls_back(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            backend = self.backend(directory)
            self.config_file(backend / "openai.local.json", {"api_key": KEY_A})
            self.key_file(backend / "oversized.key", "x" * 16385)
            observed = self.observe(backend, directory, directory / "home",
                                    {"OPENAI_API_KEY_FILE": "oversized.key"})
            self.assertEqual(observed["keyHash"], hashlib.sha256(b"").hexdigest())


class DeploymentCredentialCacheTests(unittest.TestCase):
    def test_rotating_private_config_credentials_isolates_cached_responses(self):
        with tempfile.TemporaryDirectory() as raw:
            backend = Path(raw)
            config = backend / "openai.local.json"
            seen = []
            def transport(request, timeout):
                seen.append(request.get_header("Authorization"))
                return io.BytesIO(json.dumps({"status": "completed", "output": [
                    {"type": "message", "content": [{"type": "output_text", "text": "Account response " + str(len(seen))}]}]}).encode())
            trace = {"distance": 1, "breakdown": {"temporal": 0, "quantifier": 0, "matrix": 1},
                     "operations": [{"kind": "replace", "component": "matrix", "cost": 1}]}
            with patch.object(luna, "BACKEND_ROOT", backend), patch.dict(os.environ, {"OPENAI_DISABLED": "0"}, clear=True):
                client = luna.Explainer(transport=transport)
                config.write_text(json.dumps({"api_key": KEY_A}))
                config.chmod(0o600)
                first = client.explain(trace)
                config.write_text(json.dumps({"api_key": KEY_B}))
                second = client.explain(trace)
                config.write_text(json.dumps({"api_key": KEY_A}))
                repeated = client.explain(trace)
            self.assertEqual(first["status"], "ok")
            self.assertEqual(second["status"], "ok")
            self.assertNotEqual(first["text"], second["text"])
            self.assertEqual(repeated, first)
            self.assertEqual(seen, ["Bearer " + KEY_A, "Bearer " + KEY_B])
            self.assertNotIn(KEY_A, repr(client.cache))
            self.assertNotIn(KEY_B, repr(client.cache))


class CredentialHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(("127.0.0.1", 0))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.app.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()

    def test_private_config_and_key_paths_are_not_served(self):
        for path in ("/openai.local.json", "/backend/openai.local.json", "/secrets/openai.key",
                     "/openai.example.json", "/%2e%2e/openai.local.json", "/openai.local.json?download=1"):
            with self.subTest(path=path), self.assertRaises(HTTPError) as context:
                urlopen(self.base + path, timeout=5)
            self.assertEqual(context.exception.code, 404)

    def test_http_payload_and_header_cannot_replace_deployment_credentials(self):
        payload = {"exerciseId": "graphs-inv1", "body": "some Node", "revision": 3}
        for field in ("api_key", "api_key_file", "apiKey"):
            request = Request(self.base + "/api/explain", data=json.dumps(dict(payload, **{field: KEY_A})).encode(),
                              headers={"Content-Type": "application/json"})
            with patch.object(self.app, "evaluate") as evaluate, self.assertRaises(HTTPError) as context:
                urlopen(request, timeout=5)
            self.assertEqual(context.exception.code, 400)
            evaluate.assert_not_called()
        request = Request(self.base + "/api/explain", data=json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json", "X-OpenAI-API-Key": KEY_A})
        feedback = {"status": "ok", "distance": 0}
        with patch.object(self.app, "evaluate", return_value=feedback), \
             patch.object(self.app.explainer, "explain", return_value={"status": "disabled"}) as explain:
            with urlopen(request, timeout=5) as response:
                body = response.read().decode()
        explain.assert_called_once_with(feedback)
        self.assertNotIn(KEY_A, body)


class CredentialDeliveryTests(unittest.TestCase):
    def test_shipped_example_is_empty_and_private_paths_are_ignored(self):
        self.assertEqual(json.loads((ROOT / "openai.example.json").read_text()), {"api_key": ""})
        rules = (ROOT / ".gitignore").read_text().splitlines()
        self.assertIn("openai.local.json", rules)
        self.assertIn("secrets/", rules)

    def test_closure_snapshot_omits_local_config_and_secrets_directory(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "openai.local.json").write_text(json.dumps({"api_key": KEY_A}))
            (directory / "secrets").mkdir()
            (directory / "secrets/private-data.txt").write_text(KEY_B)
            (directory / "openai.example.json").write_text('{"api_key":""}')
            files = inventory(directory)
            self.assertEqual([item["path"] for item in files], ["openai.example.json"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
