"""Guard all JVM/Android targets and reproduce Kotlin's inline-bytecode constraint.

The compiler fixture uses no SDK substitutes, but is not an Android/Gradle build.
"""
from pathlib import Path
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class JvmBytecodeTargetsTest(unittest.TestCase):
    def test_all_modules_align_java_and_kotlin_at_jvm_11(self):
        for module in ('app', 'core', 'shared-domain', 'ui-contract', 'ui-foundation',
                       'ui-model', 'ui', 'desktop', 'desktop-compose'):
            with self.subTest(module=module):
                text = (ROOT / module / 'build.gradle').read_text()
                self.assertIn('JvmTarget.JVM_11', text)
                self.assertIn('sourceCompatibility', text)
                self.assertIn('targetCompatibility', text)
                self.assertEqual(2, text.count('JavaVersion.VERSION_11'))
                self.assertNotIn('JvmTarget.JVM_1_8', text)
                self.assertNotIn('JavaVersion.VERSION_1_8', text)
        # Both Android and desktop compilations of multiplatform modules need the target.
        for module in ('shared-domain', 'ui-contract', 'ui-model', 'ui', 'ui-foundation'):
            self.assertEqual(2, (ROOT / module / 'build.gradle').read_text().count('JvmTarget.JVM_11'))

    def test_sandbox_compilers_use_the_production_target(self):
        import re
        invocations = 0
        for script in (ROOT / 'scripts').rglob('*.sh'):
            for target in re.findall(r'-jvm-target\s+([0-9.]+)', script.read_text()):
                self.assertEqual('11', target, str(script))
                invocations += 1
        self.assertGreaterEqual(invocations, 20)
        shim = (ROOT / 'scripts/tests/test_jvm_test_suite.py').read_text()
        self.assertIn('[kotlinc, "-jvm-target", "11", "-nowarn",', shim)

    def test_real_compiler_rejects_8_to_11_inline_and_accepts_aligned_target(self):
        toolchain = Path(os.environ.get('KOTLIN_SANDBOX_TOOLCHAIN', '/tmp/kotlin-sandbox-toolchain'))
        compiler = os.environ.get('KOTLINC') or shutil.which('kotlinc-jvm') or shutil.which('kotlinc')
        if not compiler and (toolchain / 'kotlin/bin/kotlinc-jvm').is_file():
            compiler = str(toolchain / 'kotlin/bin/kotlinc-jvm')
        if not compiler:
            self.skipTest('Kotlin compiler required; run scripts/kotlin-sandbox/bootstrap-toolchain.sh')
        env = os.environ.copy()
        runtime = toolchain / 'jdk/jdk4py/java-runtime'
        if not env.get('JAVA_HOME') and runtime.is_dir():
            env['JAVA_HOME'] = str(runtime)

        def compile_source(source, target, output, *extra):
            return subprocess.run([compiler, '-jvm-target', target, str(source), '-d', str(output), *extra],
                                  env=env, capture_output=True, text=True, timeout=90)

        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            library = work / 'Library.kt'
            library.write_text('package bytecodetest\ninline fun inlineValue(): String = "ok"\n')
            library_jar = work / 'library.jar'
            result = compile_source(library, '11', library_jar)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            client = work / 'Client.kt'
            client.write_text('package bytecodetest\nfun value(): String = inlineValue()\n')
            result = compile_source(client, '1.8', work / 'bad.jar', '-cp', str(library_jar))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('cannot inline bytecode built with jvm target 11', (result.stdout + result.stderr).lower())
            output = work / 'client.jar'
            result = compile_source(client, '11', output, '-cp', str(library_jar))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            with zipfile.ZipFile(output) as jar:
                header = jar.read('bytecodetest/ClientKt.class')[:8]
            magic, minor, major = struct.unpack('>IHH', header)
            self.assertEqual(0xCAFEBABE, magic)
            self.assertEqual(55, major)  # Java 11 classfile
