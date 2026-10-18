"""Resource-reference and translation-integrity guards.

* every ``R.<type>.<name>`` referenced from Kotlin resolves in the app resources (framework
  ``android.R`` and library-provided resources are excluded),
* every menu id used from code is declared,
* every string resource with positional format arguments has the same argument set in every
  translation (a missing placeholder silently drops the formatted value — the Arabic
  ``dialog_msg_confirm_delete`` lost the account name this way), and
* the translations define no ids the default locale does not define.
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP_SRC = ROOT / "app/src"
RES = APP_SRC / "main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Resources supplied by the compiled dependencies (AppCompat, Material, design, AboutLibraries,
# materialprogressbar, zxing) rather than by this project's res/ tree.
LIBRARY_PROVIDED = {
    ("string", "appbar_scrolling_view_behavior"),
    ("style", "TextAppearance.AppCompat.Headline"),
    ("style", "TextAppearance.AppCompat.Subhead"),
    ("style", "TextAppearance.AppCompat.Widget.TextView.SpinnerItem"),
    ("style", "Widget.AppCompat.Button.Borderless.Colored"),
    ("style", "Widget.AppCompat.Button.ButtonBar.AlertDialog"),
    ("style", "Widget.MaterialProgressBar.ProgressBar.Horizontal.NoPadding"),
    ("raw", "aboutlibraries"),
    ("layout", "part_author_extra"),  # fdroid flavor source set
    ("attr", "actionBarPopupTheme"),
    ("attr", "actionBarSize"),
    ("attr", "colorPrimary"),
    ("attr", "editTextBackground"),
}


def collect_resources():
    """type -> set of names defined by the main, fdroid and play res trees."""
    resources = {}
    for res_dir in (RES, APP_SRC / "fdroid/res", APP_SRC / "play/res"):
        if not res_dir.is_dir():
            continue
        for directory in res_dir.iterdir():
            if not directory.is_dir():
                continue
            for file in directory.iterdir():
                if not file.is_file():
                    continue
                name, _, ext = file.name.partition(".")
                if directory.name.startswith("values"):
                    if ext != "xml":
                        continue
                    try:
                        root = ET.parse(file).getroot()
                    except ET.ParseError as error:
                        raise AssertionError(f"unparseable resource file {file}: {error}")
                    for element in root:
                        tag = "array" if element.tag in ("string-array", "integer-array") else element.tag
                        if tag in ("string", "color", "dimen", "style", "attr", "integer", "bool",
                                   "array", "plurals", "drawable", "id"):
                            # aapt turns a style's dotted name into an underscored constant.
                            name = element.attrib["name"].replace(".", "_") if tag == "style" \
                                else element.attrib["name"]
                            resources.setdefault(tag, set()).add(name)
                        elif element.tag == "declare-styleable":
                            styleable = element.attrib["name"]
                            resources.setdefault("styleable", set()).add(styleable)
                            for attribute in element.findall("attr"):
                                if "format" in attribute.attrib:
                                    attr_name = attribute.attrib["name"].replace(":", "_")
                                    resources.setdefault("attr", set()).add(attr_name)
                                    # R.styleable.<Styleable>_<attr> index constants.
                                    resources.setdefault("styleable", set()).add(
                                        f"{styleable}_{attr_name}")
                        elif element.tag == "item" and element.attrib.get("type"):
                            resources.setdefault(element.attrib["type"], set()).add(
                                element.attrib["name"])
                elif directory.name.startswith("drawable"):
                    if ext in ("xml", "png", "jpg", "webp", "gif"):
                        resources.setdefault("drawable", set()).add(name)
                elif directory.name.startswith("mipmap"):
                    if ext in ("xml", "png", "jpg", "webp"):
                        resources.setdefault("mipmap", set()).add(name)
                elif directory.name in ("layout", "menu", "xml", "raw", "anim", "animator", "font"):
                    resources.setdefault(directory.name, set()).add(name)
    # ids declared anywhere with @+id/
    for file in RES.rglob("*.xml"):
        for match in re.finditer(r"@\+id/([A-Za-z0-9_.]+)", file.read_text(encoding="utf-8")):
            resources.setdefault("id", set()).add(match.group(1))
    return resources


def kotlin_files():
    roots = [APP_SRC / "main/kotlin", APP_SRC / "fdroid/kotlin", APP_SRC / "play/kotlin"]
    for root in roots:
        yield from sorted(root.rglob("*.kt"))


def format_arguments(text):
    """The sorted set of positional format arguments in a resource string."""
    return tuple(sorted(re.findall(r"%\d+\$[sdf]", text)))


class ResourceReferenceTest(unittest.TestCase):
    def test_kotlin_resource_references_resolve(self):
        resources = collect_resources()
        missing = {}
        for source in kotlin_files():
            text = source.read_text(encoding="utf-8")
            for match in re.finditer(r"(?<!android\.)\bR\.([a-zA-Z]+)\.([A-Za-z0-9_]+)", text):
                resource_type, name = match.groups()
                if (resource_type, name) in LIBRARY_PROVIDED:
                    continue
                if name not in resources.get(resource_type, set()):
                    missing.setdefault(f"R.{resource_type}.{name}", []).append(source.name)
        self.assertEqual({}, missing, "Kotlin references resources that are not defined")

    def test_menu_ids_used_from_code_exist(self):
        declared = collect_resources().get("id", set())
        used = set()
        for source in kotlin_files():
            used.update(re.findall(r"R\.id\.(menu_[A-Za-z0-9_]+)", source.read_text(encoding="utf-8")))
        self.assertTrue(used)
        self.assertEqual(set(), used - declared, "menu ids used from code are not declared")


class TranslationIntegrityTest(unittest.TestCase):
    def translations(self):
        for directory in sorted(RES.iterdir()):
            if directory.is_dir() and directory.name.startswith("values-"):
                for file in sorted(directory.glob("*.xml")):
                    yield directory.name, file

    def test_format_arguments_match_default(self):
        """Every translated string must use the same positional format arguments as the default."""
        defaults = {}
        for file in sorted((RES / "values").glob("*.xml")):
            for element in ET.parse(file).getroot().findall("string"):
                defaults[element.attrib["name"]] = format_arguments("".join(element.itertext()))

        mismatches = []
        for language, file in self.translations():
            for element in ET.parse(file).getroot().findall("string"):
                name = element.attrib["name"]
                if name not in defaults:
                    continue
                arguments = format_arguments("".join(element.itertext()))
                if defaults[name] and arguments != defaults[name]:
                    mismatches.append(
                        f"{language}/{file.name}: {name} has {arguments or 'no placeholders'}, "
                        f"default has {defaults[name]}"
                    )
        self.assertEqual([], mismatches,
                         "translations must keep the default locale's format placeholders "
                         "(getString drops the argument otherwise)")

    def test_translations_define_no_unknown_strings(self):
        defaults = set()
        for file in (RES / "values").glob("*.xml"):
            defaults.update(element.attrib["name"] for element in ET.parse(file).getroot().findall("string"))
        unknown = []
        for language, file in self.translations():
            for element in ET.parse(file).getroot().findall("string"):
                if element.attrib["name"] not in defaults:
                    unknown.append(f"{language}/{file.name}: {element.attrib['name']}")
        self.assertEqual([], unknown, "translations must not define strings the default locale lacks")

    def test_resource_files_are_well_formed_xml(self):
        for file in sorted(RES.rglob("*.xml")):
            try:
                ET.parse(file)
            except ET.ParseError as error:
                self.fail(f"{file.relative_to(ROOT)} is not well-formed XML: {error}")


if __name__ == "__main__":
    unittest.main()
