"""Every relative markdown link in the docs must resolve to a file in the checkout."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = [Path("README.md"), *sorted(path.relative_to(ROOT) for path in (ROOT / "docs").glob("*.md"))]

LINK = re.compile(r"\]\(([^)#\s]+)(?:#[^)\s]*)?\)")


def relative_links(document):
    text = (ROOT / document).read_text(encoding="utf-8")
    for match in LINK.finditer(text):
        target = match.group(1)
        if target.startswith(("http://", "https://", "mailto:", "irc://")):
            continue
        yield target


class DocumentationLinkTest(unittest.TestCase):
    def test_relative_links_resolve(self):
        broken = []
        for document in DOCUMENTS:
            for target in relative_links(document):
                resolved = (ROOT / document.parent / target).resolve()
                if not resolved.exists():
                    broken.append(f"{document}: {target}")
        self.assertEqual([], broken, "documentation links point at files that are not in the checkout")

    def test_docs_referenced_from_readme_exist(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        for target in relative_links(Path("README.md")):
            if target.startswith("./"):
                target = target[2:]
            if target.endswith(".md"):
                self.assertTrue((ROOT / target).exists(), f"README references missing doc {target}")


if __name__ == "__main__":
    unittest.main()
