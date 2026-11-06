"""About section must display the required copyright and links on both clients."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALUES = ROOT / "app/src/main/res/values"
LAYOUT = ROOT / "app/src/main/res/layout/fragment_about.xml"
FRAGMENT = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Fragments/BaseAboutFragment.kt"
DESKTOP_ABOUT = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/AboutDialog.kt"
DESKTOP_WINDOW = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/MainWindow.kt"

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
        self.assertIn(WEBSITE, kt)
        self.assertIn(RELEASES, kt)
        self.assertIn("about_layout_website", kt)
        self.assertIn("about_layout_releases", kt)
        self.assertIn("aboutImgWebsite", kt)
        self.assertIn("aboutImgReleases", kt)

    def test_desktop_has_about_dialog_with_same_copyright(self):
        self.assertTrue(DESKTOP_ABOUT.exists(), "Desktop AboutDialog.kt must exist")
        kt = DESKTOP_ABOUT.read_text(encoding="utf-8")
        self.assertIn(REQUIRED_COPYRIGHT, kt)
        self.assertIn(WEBSITE, kt)
        self.assertIn(RELEASES, kt)
        self.assertIn("WEBSITE_URI", kt)
        self.assertIn("RELEASES_URI", kt)
        self.assertIn("COPYRIGHT", kt)

    def test_desktop_menu_exposes_about(self):
        window = DESKTOP_WINDOW.read_text(encoding="utf-8")
        self.assertIn("About OfflineOTP", window)
        self.assertIn("AboutDialog.show", window)
        self.assertIn("Help", window)

    def test_readme_and_docs_mention_about_copyright(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn(REQUIRED_COPYRIGHT, readme)
        self.assertIn(WEBSITE, readme)
        self.assertIn(RELEASES, readme)
        self.assertIn("About", readme)


if __name__ == "__main__":
    unittest.main()
