"""Guards for the shared, Android-free ``:core`` Gradle module.

The core is the part of the app that the desktop JVM client reuses. It therefore has a hard
boundary: no ``android.*``/``androidx.*`` imports, no third-party library that ``core/build.gradle``
does not declare, and its source list in the Kotlin sandbox harness must stay an exact, disjoint
mirror of the physical ``core/`` tree. These checks fail loudly if a later change quietly drags an
Android dependency into the module or lets the harness drift behind the checkout.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE_MAIN = ROOT / "core/src/main/kotlin"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class KotlinCoreBoundaryTest(unittest.TestCase):

    def test_core_sources_never_import_android(self):
        """The desktop client must build :core without an Android SDK on the classpath."""
        offenders = []
        for path in CORE_MAIN.rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith("import "):
                    imported = stripped[len("import "):].split()[0].strip(".")
                    if imported == "android" or imported.startswith(("android.", "androidx.")):
                        offenders.append(f"{path.relative_to(ROOT)}:{idx} {imported}")
        self.assertEqual([], offenders, "core sources must not import android.* or androidx.*")

    def test_core_third_party_imports_stay_declared(self):
        """Any non-kotlin/java import in core must be a dependency core/build.gradle declares."""
        allowed_prefixes = ("org.apache.commons.codec",)
        offenders = []
        for path in CORE_MAIN.rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if not stripped.startswith("import "):
                    continue
                imported = stripped[len("import "):].split()[0].strip(".")
                if imported.startswith(("kotlin", "java", "javax")):
                    continue
                if not any(imported == p or imported.startswith(p + ".") for p in allowed_prefixes):
                    offenders.append(f"{path.relative_to(ROOT)}:{idx} {imported}")
        self.assertEqual([], offenders, "core has an undeclared third-party import")

    def test_run_sh_core_list_matches_tree_and_is_disjoint(self):
        """CORE_SOURCES must enumerate exactly the physical core tree and never overlap APP_SOURCES."""
        text = (SANDBOX / "run.sh").read_text(encoding="utf-8")

        def parse(variable):
            start = text.index(f"{variable}=(")
            end = text.index(")", start)
            return [line.strip().strip('"')
                    for line in text[start:end].splitlines()[1:]
                    if line.strip().startswith('"')]

        core_listed = {
            e.replace("$KOTLIN_CORE", "core/src/main/kotlin/org/shadowice/flocke/andotp")
             .replace("$ROOT", ".")
            for e in parse("CORE_SOURCES")
        }
        app_listed = {
            e.replace("$KOTLIN_APP", "app/src/main/kotlin/org/shadowice/flocke/andotp")
             .replace("$ROOT", ".")
            for e in parse("APP_SOURCES")
        }
        core_on_disk = {str(p.relative_to(ROOT)) for p in CORE_MAIN.rglob("*.kt")}

        self.assertEqual(core_on_disk, core_listed, "run.sh CORE_SOURCES drifted from core/ tree")
        self.assertEqual(set(), core_listed & app_listed, "a source is listed in both core and app")

    def test_gradle_wiring_present(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        app_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn('include ":core"', settings)
        self.assertIn("project(':core')", app_gradle)
        self.assertTrue((ROOT / "core/build.gradle").is_file())


if __name__ == "__main__":
    unittest.main()
