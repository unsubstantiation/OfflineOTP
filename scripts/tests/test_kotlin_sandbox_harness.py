"""Guards for the Kotlin sandbox harness itself.

The sandbox is only a meaningful stand-in while its stub set cannot drift behind (or silently
grow beyond) the sources that ``run.sh`` compiles:

* a stub must never shadow a production class name (the checks would then pin the stand-in's
  behaviour instead of the real declaration), and
* the checked-in ``R`` stand-in must be exactly what ``generate-r-stub.py`` recomputes from the
  current source list (a stale stub breaks the documented verification gate, as happened when the
  thumbnail batch grew ``R.drawable.*`` references without regenerating it).
"""
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SANDBOX = ROOT / "scripts/kotlin-sandbox"
STUBS = SANDBOX / "stubs"
GENERATOR = SANDBOX / "generate-r-stub.py"


def run_sh_sources():
    """The (shell-expanded) source lists of run.sh, as written in the file."""
    text = (SANDBOX / "run.sh").read_text(encoding="utf-8")
    start = text.index("APP_SOURCES=(")
    end = text.index(")", start)
    body = text[start:end]
    return [line.strip().strip('"') for line in body.splitlines()[1:] if line.strip().startswith('"')]


def stub_declarations():
    """(relative stub path, package, top-level declaration name) for every stub file."""
    found = []
    for path in sorted(STUBS.rglob("*.kt")):
        if path.name == "R.kt":
            continue
        text = path.read_text(encoding="utf-8")
        package = ""
        for line in text.splitlines():
            if line.startswith("package "):
                package = line[len("package "):].strip()
                break
        for match in __import__("re").finditer(
            r"^(?:public\s+|internal\s+|private\s+)?(?:open\s+|abstract\s+|sealed\s+|final\s+)?"
            r"(class|object|interface|enum class|fun interface)\s+([A-Za-z0-9_]+)",
            text,
            __import__("re").MULTILINE,
        ):
            found.append((path.relative_to(STUBS), package, match.group(2)))
    return found


class KotlinSandboxHarnessTest(unittest.TestCase):
    def test_listed_sources_exist(self):
        """Every APP_SOURCES entry of run.sh must resolve to a file in the checkout."""
        missing = []
        for entry in run_sh_sources():
            expanded = (
                entry.replace("$KOTLIN_APP", "app/src/main/kotlin/org/shadowice/flocke/andotp")
                .replace("$ROOT", ".")
            )
            if not (ROOT / expanded).is_file():
                missing.append(entry)
        self.assertEqual([], missing, "run.sh lists sources that are not in the checkout")

    def test_no_stub_shadows_production_class(self):
        """A stub must not declare a type with the same fully-qualified name as an app class."""
        import re

        def production_names():
            names = set()
            roots = [ROOT / "app/src/main/kotlin"]
            roots += [ROOT / f"app/src/{flavor}/kotlin" for flavor in ("fdroid", "play")]
            for root in roots:
                for path in root.rglob("*.kt"):
                    text = path.read_text(encoding="utf-8")
                    match = re.search(r"^package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
                    package = match.group(1) if match else ""
                    names.add(f"{package}.{path.stem}" if package else path.stem)
            return names

        production = production_names()
        clashes = [
            f"{relative} declares {package}.{name}, which is also a production class"
            for relative, package, name in stub_declarations()
            if (f"{package}.{name}" if package else name) in production
        ]
        self.assertEqual([], clashes,
                         "a stand-in shadows a production class; the sandbox would check the stub, not the app")

    def test_checked_in_r_stub_is_fresh(self):
        """``generate-r-stub.py --check`` must succeed: the committed R stub matches the sources."""
        result = subprocess.run(
            [sys.executable, str(GENERATOR), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            0, result.returncode,
            "stale sandbox R stub — run: python3 scripts/kotlin-sandbox/generate-r-stub.py\n"
            f"{result.stdout}{result.stderr}",
        )


if __name__ == "__main__":
    unittest.main()
