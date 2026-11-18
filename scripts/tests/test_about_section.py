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

    def test_android_layout_has_website_and_releases_rows(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        self.assertIn("about_layout_website", layout)
        self.assertIn("about_layout_releases", layout)
        self.assertIn("aboutImgWebsite", layout)
        self.assertIn("aboutImgReleases", layout)
        self.assertIn(WEBSITE, layout)
        self.assertIn(RELEASES, layout)

    def test_android_fragment_opens_website_and_releases(self):
        kt = FRAGMENT.read_text(encoding="utf-8")
        self.assertIn("WEBSITE_URI", kt)
        self.assertIn("RELEASES_URI", kt)
        self.assertIn('WEBSITE_URI = AboutInfo.url("website")', kt)
        self.assertIn('RELEASES_URI = AboutInfo.url("releases")', kt)
        self.assertIn("about_layout_website", kt)
        self.assertIn("about_layout_releases", kt)
        self.assertIn("aboutImgWebsite", kt)
        self.assertIn("aboutImgReleases", kt)



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
        swift = (ROOT / "ios/App/Sources/SettingsView.swift").read_text()
        for field in ("description", "copyright", "attribution", "links", "thumbnailDisclaimer"):
            self.assertIn("AboutInfo." + field, dialog)
        self.assertIn('ComposeAboutHost { showingAbout = false }', swift)
        self.assertNotIn('AboutInfo.', swift)
        bridge = (ROOT / "ios/App/Sources/ComposeAboutHost.swift").read_text()
        self.assertIn('presentation.viewController(', bridge)
        self.assertIn('UIApplication.shared.open(url)', bridge)

    def test_readme_and_docs_mention_about_copyright(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn(REQUIRED_COPYRIGHT, readme)
        self.assertIn(WEBSITE, readme)
        self.assertIn(RELEASES, readme)
        self.assertIn("About", readme)


if __name__ == "__main__":
    unittest.main()
