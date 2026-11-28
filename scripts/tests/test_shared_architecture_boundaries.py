"""Executable architecture decisions for consolidation chunk 1."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("shared_boundaries", ROOT / "scripts/check_shared_boundaries.py")
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class SharedArchitectureBoundariesTest(unittest.TestCase):
    def test_repository_matches_reviewed_transitional_graph(self):
        self.assertEqual([], boundaries.check())

    def test_platform_and_engine_imports_cannot_enter_native_common_sources(self):
        for module in boundaries.COMMON_IMPORTS:
            for imported in ("java.io.File", "javax.crypto.SecretKey", "android.content.Context",
                             "platform.Foundation.NSData", "org.json.JSONObject",
                             "com.yubico.yubikit.core.Session",
                             "com.fairuniversal.offlineotp.Utilities.VaultContainer"):
                with self.subTest(module=module, imported=imported):
                    self.assertTrue(boundaries.source_errors(f"import {imported}", module))

    def test_qualified_platform_uses_are_rejected_without_imports(self):
        for expression in ("java.io.File(path)", "org.json.JSONObject(text)",
                           "platform.Foundation.NSData()", "kotlinx.cinterop.memScoped {}"):
            self.assertTrue(boundaries.source_errors(f"val x = {expression}", "ui-foundation"))

    def test_contract_does_not_gain_compose_but_foundation_can_use_it(self):
        text = "import androidx.compose.runtime.Composable"
        self.assertTrue(boundaries.source_errors(text, "ui-contract"))
        self.assertEqual([], boundaries.source_errors(text, "ui-foundation"))
        self.assertEqual([], boundaries.source_errors("import kotlin.math.max", "ui-contract"))

    def test_dependency_extraction_includes_export_and_ignores_comments(self):
        text = """
        // implementation project(':app')
        /* api project(':desktop') */
        api project(':ui-contract')
        export rootProject.findProject(':ui-contract')
        implementation(project(":core"))
        """
        self.assertEqual({"core", "ui-contract"}, boundaries.project_dependencies(text))

    def test_comment_mentions_do_not_violate_boundaries(self):
        text = "// java.io.File is an adapter detail\n/* import android.content.Context */"
        self.assertEqual([], boundaries.source_errors(text, "ui-contract"))
