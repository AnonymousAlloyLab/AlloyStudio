"""Fresh portable compilation and failure-atomic publication witnesses."""
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import io
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from runtime_dependencies import JAR_FILES, REQUIRED_CLASSES
from scripts import build_engine

ROOT = Path(__file__).resolve().parents[1]
HEADER = b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 61)


def snapshot(path):
    return {str(file.relative_to(path)): hashlib.sha256(file.read_bytes()).hexdigest()
            for file in path.rglob('*') if file.is_file()}


def fake_classes(path):
    for name in REQUIRED_CLASSES:
        file = path / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(HEADER)


def copy_source(source, destination):
    def copy_file(original, target):
        if str(original).endswith('.jar'):
            try:
                os.link(original, target)
                return target
            except OSError:
                pass
        return shutil.copy2(original, target)
    for directory in ('engine/src', 'vendor/acgn'):
        shutil.copytree(source / directory, destination / directory, copy_function=copy_file)
    shutil.copy2(source / 'runtime_dependencies.py', destination / 'runtime_dependencies.py')
    (destination / 'scripts').mkdir()
    shutil.copy2(ROOT / 'scripts/build_engine.py', destination / 'scripts/build_engine.py')


class CleanEngineBuildTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed_directory = tempfile.TemporaryDirectory(prefix='alloy-clean-compiler-seed-')
        cls.addClassCleanup(cls.seed_directory.cleanup)
        cls.seed = Path(cls.seed_directory.name)
        copy_source(ROOT, cls.seed)
        cls.javac = shutil.which('javac')
        if cls.javac is None:
            raise RuntimeError('A JDK 17+ is required for the real clean-build witness.')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-clean-compiler-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'source checkout with spaces'
        self.root.mkdir()
        copy_source(self.seed, self.root)
        self.output = self.root / 'build/engine/classes'
        self.calls = []

    def compile_fake(self, command, **options):
        self.calls.append((command, options))
        fake_classes(Path(command[command.index('-d') + 1]))
        return subprocess.CompletedProcess(command, 0, b'', b'')

    def old_output(self):
        self.output.mkdir(parents=True)
        (self.output / 'Old.class').write_bytes(HEADER + b'old')
        return snapshot(self.output)

    def assert_no_staging(self):
        if self.output.parent.exists():
            self.assertEqual(list(self.output.parent.glob('.classes.compile-*')), [])
            self.assertEqual(list(self.output.parent.glob('.classes.backup-*')), [])

    def test_real_hint_source_change_rebuilds_bytes_and_removes_obsolete_classes(self):
        first = build_engine.compile_engine(self.root, compiler=self.javac)
        self.assertEqual(first, self.output)
        relative = Path('is/fivefivefive/CanDis/LiveTrace.class')
        before = (first / relative).read_bytes()
        source = self.root / 'engine/src/is/fivefivefive/CanDis/LiveTrace.java'
        old = 'Review this part of your expression.'
        changed = 'Fresh compiler regression hint marker.'
        text = source.read_text()
        self.assertEqual(text.count(old), 1)
        source.write_text(text.replace(old, changed))
        (first / 'Obsolete.class').write_bytes(HEADER + b'obsolete')
        second = build_engine.compile_engine(self.root, compiler=self.javac)
        after = (second / relative).read_bytes()
        self.assertNotEqual(before, after)
        self.assertIn(changed.encode(), after)
        self.assertNotIn(old.encode(), after)
        self.assertFalse((second / 'Obsolete.class').exists())
        self.assertTrue(all((second / name).is_file() for name in REQUIRED_CLASSES))
        self.assert_no_staging()

    def test_explicit_native_paths_three_entrypoints_and_sanitized_java_environment(self):
        injected = {name: 'PRIVATE_JAVA_OPTION' for name in build_engine.JAVA_ENVIRONMENT}
        injected['Classpath'] = 'PRIVATE_MIXED_CASE'
        injected['KEEP_BUILD_SETTING'] = 'retained'
        with patch.dict(os.environ, injected), patch.object(build_engine.subprocess, 'run', side_effect=self.compile_fake):
            result = build_engine.compile_engine(self.root, compiler='/native JDK/bin/javac')
        self.assertEqual(result, self.output)
        command, options = self.calls[0]
        self.assertEqual(command[:6], ['/native JDK/bin/javac', '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source'])
        self.assertEqual(command[command.index('-cp') + 1], os.pathsep.join(
            str(self.root / 'vendor/acgn/lib' / name) for name in JAR_FILES))
        self.assertEqual(command[command.index('-sourcepath') + 1], os.pathsep.join(
            str(self.root / name) for name in ('engine/src', 'vendor/acgn/src')))
        self.assertNotIn('*', command[command.index('-cp') + 1])
        self.assertEqual(command[-3:], [str(self.root / 'engine/src/live' / name)
                                        for name in build_engine.ENTRY_POINTS])
        staging = Path(command[command.index('-d') + 1])
        self.assertEqual(staging.parent, self.output.parent)
        self.assertNotEqual(staging, self.output)
        self.assertEqual(options['cwd'], self.root)
        self.assertEqual(options['timeout'], 180)
        self.assertTrue(options['capture_output'])
        self.assertFalse(options['check'])
        self.assertTrue(all(name.upper() not in build_engine.JAVA_ENVIRONMENT for name in options['env']))
        self.assertEqual(options['env']['KEEP_BUILD_SETTING'], 'retained')

    def test_relative_custom_output_uses_root_and_external_output_allows_spaces(self):
        elsewhere = self.base / 'unrelated working directory'
        elsewhere.mkdir()
        original = Path.cwd()
        try:
            os.chdir(elsewhere)
            with patch.object(build_engine.subprocess, 'run', side_effect=self.compile_fake):
                local = build_engine.compile_engine(self.root, output='custom class output')
                external = build_engine.compile_engine(self.root, output=self.base / 'outside classes with spaces')
        finally:
            os.chdir(original)
        self.assertEqual(local, self.root / 'custom class output')
        self.assertEqual(external, self.base / 'outside classes with spaces')
        self.assertTrue((local / REQUIRED_CLASSES[0]).exists())
        self.assertFalse(self.output.exists())

    def test_partial_compiler_failure_preserves_old_classes_and_existing_zip(self):
        before = self.old_output()
        archive = self.root / 'alloy-studio-iis.zip'
        archive.write_bytes(b'previous archive')
        def fail(command, **options):
            staging = Path(command[command.index('-d') + 1])
            (staging / 'Partial.class').write_bytes(HEADER)
            return subprocess.CompletedProcess(command, 1, b'PRIVATE_STDOUT', b'PRIVATE_STDERR')
        with patch.object(build_engine.subprocess, 'run', side_effect=fail):
            with self.assertRaises(build_engine.BuildError) as caught:
                build_engine.compile_engine(self.root)
        self.assertNotIn('PRIVATE_', str(caught.exception))
        self.assertEqual(snapshot(self.output), before)
        self.assertEqual(archive.read_bytes(), b'previous archive')
        self.assert_no_staging()

    def test_timeout_and_unavailable_compiler_preserve_old_tree(self):
        before = self.old_output()
        failures = [subprocess.TimeoutExpired('PRIVATE_COMMAND', 180, output=b'PRIVATE_OUTPUT'),
                    FileNotFoundError('PRIVATE_PATH')]
        for failure in failures:
            with patch.object(build_engine.subprocess, 'run', side_effect=failure):
                with self.assertRaises(build_engine.BuildError) as caught:
                    build_engine.compile_engine(self.root)
            self.assertNotIn('PRIVATE_', str(caught.exception))
            self.assertEqual(snapshot(self.output), before)
            self.assert_no_staging()

    def test_incomplete_invalid_or_preview_class_trees_are_not_published(self):
        before = self.old_output()
        for mode in ('missing', 'badmagic', 'newer', 'older', 'preview', 'unrelated'):
            def invalid(command, **options):
                staging = Path(command[command.index('-d') + 1])
                fake_classes(staging)
                target = staging / REQUIRED_CLASSES[-1]
                if mode == 'missing': target.unlink()
                elif mode == 'badmagic': target.write_bytes(b'notaclass')
                elif mode == 'newer': target.write_bytes(b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 62))
                elif mode == 'older': target.write_bytes(b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 60))
                elif mode == 'preview': target.write_bytes(b'\xca\xfe\xba\xbe' + struct.pack('>HH', 65535, 61))
                else: (staging / 'unrelated.txt').write_text('PRIVATE_FILE')
                return subprocess.CompletedProcess(command, 0)
            with self.subTest(mode=mode), patch.object(build_engine.subprocess, 'run', side_effect=invalid):
                with self.assertRaises(build_engine.BuildError): build_engine.compile_engine(self.root)
            self.assertEqual(snapshot(self.output), before)
            self.assert_no_staging()

    def test_promotion_failure_restores_old_tree(self):
        before = self.old_output()
        replace = Path.replace
        def fail_staging(path, target):
            if path.name.startswith('.classes.compile-'):
                raise PermissionError('PRIVATE_PROMOTION_FAILURE')
            return replace(path, target)
        with patch.object(build_engine.subprocess, 'run', side_effect=self.compile_fake), patch.object(Path, 'replace', fail_staging):
            with self.assertRaises(build_engine.BuildError) as caught:
                build_engine.compile_engine(self.root)
        self.assertNotIn('PRIVATE_', str(caught.exception))
        self.assertEqual(snapshot(self.output), before)
        self.assert_no_staging()

    def test_each_missing_dependency_fails_before_compiler_or_staging(self):
        before = self.old_output()
        for name in JAR_FILES:
            jar = self.root / 'vendor/acgn/lib' / name
            hidden = jar.with_suffix('.held')
            jar.rename(hidden)
            try:
                with patch.object(build_engine.subprocess, 'run') as compiler:
                    with self.assertRaises(build_engine.BuildError): build_engine.compile_engine(self.root)
                    compiler.assert_not_called()
            finally:
                hidden.rename(jar)
            self.assertEqual(snapshot(self.output), before)
            self.assert_no_staging()

    def test_sources_project_ancestors_and_protected_directories_cannot_be_outputs(self):
        candidates = [self.root, self.root.parent, Path(self.root.anchor), self.root / 'build',
                      self.root / 'engine', self.root / 'engine/build', self.root / 'engine/src/newclasses',
                      *(self.root / name / 'classes' for name in build_engine.PROTECTED_DIRECTORIES)]
        for output in candidates:
            with self.subTest(output=str(output.relative_to(self.base)) if output.is_relative_to(self.base) else 'ancestor'), patch.object(build_engine.subprocess, 'run') as compiler:
                with self.assertRaises(build_engine.BuildError):
                    build_engine.compile_engine(self.root, output=output)
                compiler.assert_not_called()
        with patch.object(build_engine.subprocess, 'run', side_effect=self.compile_fake):
            self.assertEqual(build_engine.compile_engine(self.root, output='engine/build/classes'),
                             self.root / 'engine/build/classes')

    def test_unrelated_output_files_are_preserved_and_compiler_is_not_run(self):
        self.output.mkdir(parents=True)
        data = self.output / 'notes.txt'
        data.write_text('PRIVATE_USER_DATA')
        with patch.object(build_engine.subprocess, 'run') as compiler:
            with self.assertRaises(build_engine.BuildError) as caught:
                build_engine.compile_engine(self.root)
            compiler.assert_not_called()
        self.assertEqual(data.read_text(), 'PRIVATE_USER_DATA')
        self.assertNotIn('PRIVATE_', str(caught.exception))
        self.assert_no_staging()

    def test_output_and_nested_symlinks_are_rejected_without_touching_targets(self):
        target = self.base / 'linked class target'
        target.mkdir()
        (target / 'Precious.class').write_bytes(b'PRIVATE_USER_DATA')
        self.output.parent.mkdir(parents=True)
        self.output.symlink_to(target, target_is_directory=True)
        with patch.object(build_engine.subprocess, 'run') as compiler:
            with self.assertRaises(build_engine.BuildError): build_engine.compile_engine(self.root)
            compiler.assert_not_called()
        self.output.unlink()
        self.output.mkdir()
        (self.output / 'linked').symlink_to(target, target_is_directory=True)
        with patch.object(build_engine.subprocess, 'run') as compiler:
            with self.assertRaises(build_engine.BuildError): build_engine.compile_engine(self.root)
            compiler.assert_not_called()
        self.assertEqual((target / 'Precious.class').read_bytes(), b'PRIVATE_USER_DATA')

    def test_parent_alias_cannot_bypass_source_directory_protection(self):
        alias = self.base / 'alias to sources'
        alias.symlink_to(self.root / 'engine/src', target_is_directory=True)
        with patch.object(build_engine.subprocess, 'run') as compiler:
            with self.assertRaises(build_engine.BuildError):
                build_engine.compile_engine(self.root, output=alias / 'newclasses')
            compiler.assert_not_called()

    def test_existing_empty_directories_are_safe_and_obsolete_classes_disappear(self):
        self.output.mkdir(parents=True)
        (self.output / 'empty/subdirectory').mkdir(parents=True)
        (self.output / 'Old.class').write_bytes(HEADER)
        with patch.object(build_engine.subprocess, 'run', side_effect=self.compile_fake):
            build_engine.compile_engine(self.root)
        self.assertFalse((self.output / 'Old.class').exists())
        self.assertFalse((self.output / 'empty').exists())
        self.assert_no_staging()

    def test_missing_entrypoint_fails_without_compiler(self):
        (self.root / 'engine/src/live/BehaviorFeedback.java').unlink()
        with patch.object(build_engine.subprocess, 'run') as compiler:
            with self.assertRaises(build_engine.BuildError): build_engine.compile_engine(self.root)
            compiler.assert_not_called()

    def test_cli_relies_on_script_root_from_unrelated_working_directory(self):
        elsewhere = self.base / 'elsewhere'
        elsewhere.mkdir()
        command = [sys.executable, '-I', str(self.root / 'scripts/build_engine.py'),
                   '--output', 'CLI classes with spaces', '--javac', self.javac]
        result = subprocess.run(command, cwd=elsewhere, capture_output=True, timeout=90, check=False)
        self.assertEqual(result.returncode, 0, 'Real isolated CLI build failed')
        self.assertEqual(result.stderr, b'')
        self.assertIn(b'Built fresh Java 17 engine classes', result.stdout)
        output = self.root / 'CLI classes with spaces'
        self.assertTrue(all((output / name).is_file() for name in REQUIRED_CLASSES))
        self.assertFalse(self.output.exists())

    def test_cli_failure_returns_nonzero_without_success_or_private_compiler_streams(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(build_engine, 'compile_engine', side_effect=build_engine.BuildError('Compilation failed safely.')):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                code = build_engine.main(['--root', str(self.root)])
        self.assertEqual(code, 1)
        self.assertEqual(stdout.getvalue(), '')
        self.assertIn('Engine build failed', stderr.getvalue())
