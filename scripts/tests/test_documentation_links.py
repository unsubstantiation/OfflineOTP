"""Check local Markdown paths and in-page heading links in project documentation."""
import re
import unittest
from collections import Counter
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = [Path("README.md"), Path("CHANGELOG.md"), Path("scripts/kotlin-sandbox/README.md")]
for directory in ("docs", ".github/ISSUE_TEMPLATE", "assets/thumbnails"):
    DOCUMENTS.extend(sorted(path.relative_to(ROOT) for path in (ROOT / directory).glob("*.md")))

LINK = re.compile(r"\]\(([^)#\s]+)(?:#[^)\s]*)?\)")
IN_PAGE_LINK = re.compile(r"\]\(#([^)\s]+)\)")
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.MULTILINE)


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

    def test_in_page_links_target_headings(self):
        broken = []
        for document in DOCUMENTS:
            content = (ROOT / document).read_text(encoding="utf-8")
            counts = Counter()
            anchors = set()
            for heading in HEADING.findall(content):
                slug = re.sub(r"[^\w -]", "", heading.lower()).replace(" ", "-")
                suffix = counts[slug]
                counts[slug] += 1
                anchors.add(f"{slug}-{suffix}" if suffix else slug)
            for fragment in IN_PAGE_LINK.findall(content):
                if unquote(fragment) not in anchors:
                    broken.append(f"{document}: #{fragment}")
        self.assertEqual([], broken, "in-page links point at missing headings")

    def test_docs_referenced_from_readme_exist(self):
        for target in relative_links(Path("README.md")):
            if target.startswith("./"):
                target = target[2:]
            if target.endswith(".md"):
                self.assertTrue((ROOT / target).exists(), f"README references missing doc {target}")


if __name__ == "__main__":
    unittest.main()
