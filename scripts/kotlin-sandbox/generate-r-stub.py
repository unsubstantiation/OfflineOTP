#!/usr/bin/env python3
"""Regenerate the sandbox-only R stand-in from the sources the sandbox compiles.

`scripts/kotlin-sandbox/run.sh` type-checks Kotlin sources against platform stubs. Several of those
sources reference generated resource ids (`R.string.*`, `R.drawable.*`, ...). The stub exists so those references resolve. Scalar ids are distinct and
deterministic (an aapt-like 0x7fTTEEEE layout: type byte from the sorted type names, entry index
from the sorted names), so checks can tell one resource from another, e.g. which toast was shown.
They are NOT aapt's values: nothing may depend on a specific number, only on (in)equality.

This script recomputes the exact id set from the same source lists run.sh uses, so the checked-in
stub cannot drift behind (or silently grow beyond) what the sandbox actually compiles. It is a
source scan, not a build step: run it after changing the compiled sources, then commit the result.

Usage: python3 scripts/kotlin-sandbox/generate-r-stub.py [--check]
  --check exits non-zero when the checked-in stub is stale instead of rewriting it.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

SANDBOX = Path(__file__).resolve().parent
ROOT = SANDBOX.parents[1]
KOTLIN_APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"
KOTLIN_CORE = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"

# Keep in sync with the APP_SOURCES list in run.sh. Parsing run.sh itself keeps the
# two from drifting apart.
RUN = (SANDBOX / "run.sh").read_text()


def source_lists(variable):
    match = re.search(rf"^{variable}=\((.*?)^\)", RUN, re.MULTILINE | re.DOTALL)
    if not match:
        raise SystemExit(f"could not find {variable} in run.sh")
    return re.findall(r'"([^"]+)"', match.group(1))


def expand(path):
    return (str(path)
            .replace("$KOTLIN_APP", str(KOTLIN_APP))
            .replace("$KOTLIN_CORE", str(KOTLIN_CORE))
            .replace("$ROOT", str(ROOT)))


def collect():
    groups = defaultdict(set)
    # Core sources are R-free, but scanning them too keeps this in lockstep with every list run.sh
    # compiles and fails loudly if a listed file disappears.
    for variable in ("CORE_SOURCES", "APP_SOURCES"):
        for entry in source_lists(variable):
            source = Path(expand(entry))
            if not source.is_file():
                raise SystemExit(f"listed source is missing: {source}")
            for found in re.finditer(r"\bR\.(\w+)\.(\w+)", source.read_text()):
                groups[found.group(1)].add(found.group(2))
    return groups


def styleable_declarations():
    # Styleable arrays are not scalar resource ids. Give each local attr a distinct, stable fake
    # slot so the picker checks can supply values by name. This does not emulate aapt ordering,
    # XML/reference resolution, themes, or real resource ids.
    declarations = {}
    for file in sorted((ROOT / "app/src/main/res/values").glob("*.xml")):
        for node in ET.parse(file).getroot().findall("declare-styleable"):
            declarations[node.attrib["name"]] = sorted(
                attr.attrib["name"].replace(":", "_") for attr in node.findall("attr"))
    return declarations


def render(groups):
    declarations = styleable_declarations()
    styleable_indexes = {
        f"{style}_{attr}": index
        for style, attrs in declarations.items() for index, attr in enumerate(attrs)
    }
    lines = [
        "// Sandbox-only stand-in for the generated resource class.",
        "// Regenerate with scripts/kotlin-sandbox/generate-r-stub.py after changing run.sh's",
        "// source lists. Ids are distinct stand-ins (0x7fTTEEEE), not aapt's resource ids.",
        "package org.shadowice.flocke.andotp",
        "",
        "object R {",
    ]
    scalar_types = [group for group in sorted(groups) if group != "styleable"]
    for group in sorted(groups):
        lines.append(f"    object {group} {{")
        for entry, name in enumerate(sorted(groups[group])):
            if group == "styleable":
                if name in declarations:
                    slots = ", ".join(str(i) for i in range(len(declarations[name])))
                    lines.append(f"        @JvmField val {name} = intArrayOf({slots})")
                elif name in styleable_indexes:
                    lines.append(f"        const val {name} = {styleable_indexes[name]}")
                else:
                    raise SystemExit(f"unknown styleable reference: {name}")
            else:
                type_id = scalar_types.index(group) + 1
                lines.append(f"        const val {name} = 0x7f{type_id:02x}{entry:04x}")
        lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main():
    stub_path = SANDBOX / "stubs/org/shadowice/flocke/andotp/R.kt"
    rendered = render(collect())
    if "--check" in sys.argv[1:]:
        current = stub_path.read_text()
        if current != rendered:
            print("stale R stub: regenerate with "
                  "python3 scripts/kotlin-sandbox/generate-r-stub.py", file=sys.stderr)
            return 1
        print("R stub is up to date")
        return 0
    stub_path.write_text(rendered)
    print(f"rewrote {stub_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
