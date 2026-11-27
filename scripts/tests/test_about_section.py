"""About section must display the required copyright and links on both clients."""
import unittest
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALUES = ROOT / "app/src/main/res/values"
LAYOUT = ROOT / "app/src/main/res/layout/fragment_about.xml"
FRAGMENT = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Fragments/BaseAboutFragment.kt"

REQUIRED_COPYRIGHT = "Copyright (c) 2026 unsubstantiation"
WEBSITE = "http://fairuniversal.com"
RELEASES = "https://github.com/unsubstantiation/OfflineOTP/releases"


class AboutSectionTest(unittest.TestCase):
    def test_android_strings_contain_required_copyright_and_links(self):
        strings = (VALUES / "strings_about.xml").read_text(encoding="utf-8")
        self.assertIn(REQUIRED_COPYRIGHT, strings,
                      "about_copyright must contain Copyright (c) 2026 unsubstantiation")
        self.assertIn(WEBSITE, strings, "About must reference http://fairuniversal.com")
        self.assertIn(RELEASES, strings, "About must reference releases URL")
        # Labels for the two link rows
        self.assertIn("about_label_website", strings)
        self.assertIn("about_label_releases", strings)
        # The copyright string itself must contain both URLs (user-visible requirement)
        # Find the about_copyright line
        self.assertIn(WEBSITE + " and " + RELEASES, strings.replace("\n", ""))

    def test_android_uses_shared_catalog_page_not_legacy_fragments(self):
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AboutActivity.kt").read_text()
        self.assertIn("AboutScreenComposeView(this, version, libraries, AboutFlavorLinks.extras()", activity)
        self.assertNotIn("AboutFragment", activity)
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt").read_text()
        self.assertIn("AboutInfo.links", dialog)
        self.assertIn("onOpenLink(link.url)", dialog)
        self.assertFalse(LAYOUT.exists())
        self.assertFalse(FRAGMENT.exists())

    def test_flavor_metadata_preserves_donations_without_fragment_ui(self):
        fdroid = (ROOT / "app/src/fdroid/kotlin/com/fairuniversal/offlineotp/Utilities/AboutFlavorLinks.kt").read_text()
        play = (ROOT / "app/src/play/kotlin/com/fairuniversal/offlineotp/Utilities/AboutFlavorLinks.kt").read_text()
        self.assertIn("https://flocke.shadowice.org/donate.html", fdroid)
        self.assertIn("https://richyhbm.co.uk/donate", fdroid)
        self.assertIn("emptyList()", play)
        for metadata in (fdroid, play):
            self.assertNotIn("Fragment", metadata)
            self.assertNotIn("onCreateView", metadata)

    def test_generated_content_is_fresh_for_all_three_clients(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate-about.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        catalog = json.loads((ROOT / "shared/about/content.json").read_text())
        ids = [link["id"] for link in catalog["links"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual({"website", "releases", "changelog", "license", "source", "issues",
                          "author1", "author2", "originalAuthor", "originalApp", "contributors", "support"}, set(ids))
        for link in catalog["links"]:
            self.assertTrue(link["url"].startswith(("https://", "http://fairuniversal.com")))
        self.assertEqual(4, len([key for key in catalog["strings"] if key.startswith("about_thumbnails_disclaimer_no")]))

    def test_desktop_and_ios_render_catalog_without_help_submenu(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertNotIn('Menu("Help")', host)
        self.assertIn('AboutNavigation(onAbout = { showAbout = true })', host)
        self.assertIn('desktop.browse(java.net.URI(url))', host)
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AboutDialog.kt").read_text()
        self.assertIn('verticalScroll(rememberScrollState())', dialog)
        self.assertIn('onOpenLink(link.url)', dialog)
        for field in ("description", "copyright", "attribution", "links", "thumbnailDisclaimer"):
            self.assertIn("AboutInfo." + field, dialog)
        swift = (ROOT / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        self.assertIn('case "about":', swift)
        self.assertIn('row("aboutLink:website"', swift)
        self.assertNotIn('AboutInfo.description', swift)
        self.assertIn('UIApplication.shared.open(url', (ROOT / "ios/App/Sources/AppRootViewController.swift").read_text())

    def test_readme_and_docs_mention_about_copyright(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn(REQUIRED_COPYRIGHT, readme)
        self.assertIn(WEBSITE, readme)
        self.assertIn(RELEASES, readme)
        self.assertIn("About", readme)


if __name__ == "__main__":
    unittest.main()
