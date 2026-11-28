#!/usr/bin/env python3
"""Chunk-1 architecture ratchet; source checks supplement, never replace, native builds.

Run directly or through unittest discovery. The explicit graph describes today's transitional
modules, not the final design. Change it deliberately alongside a reviewed migration slice.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PROJECT_EDGES = {
    "core": {"shared-domain"},
    "shared-domain": set(),
    "ui-contract": set(),
    "ui-foundation": {"ui-contract", "shared-domain"},
    "ui-model": {"ui-contract", "core"},
    "ui": {"ui-foundation", "ui-model", "core"},
    "desktop": {"core", "ui-model"},
    "desktop-compose": {"core", "desktop", "ui"},
    "app": {"core", "ui-model", "ui"},
}
# Only these modules are presently native-compatible. commonMain alone is not proof of that.
COMMON_IMPORTS = {
    "shared-domain": ("kotlin", "com.fairuniversal.offlineotp.domain"),
    "ui-contract": ("kotlin", "com.fairuniversal.offlineotp.ui"),
    "ui-foundation": ("kotlin", "kotlinx.coroutines", "androidx.compose",
                      "com.fairuniversal.offlineotp.ui"),
}
# Catch qualified uses as well as imports. Platform source sets are intentionally excluded.
PLATFORM_PACKAGES = (
    "java", "javax", "android", "platform", "kotlinx.cinterop", "org.json",
    "com.yubico", "com.sun", "sun",
)


def without_comments(text):
    # Keep newlines for useful diagnostics. This is a source ratchet, not a Kotlin parser.
    return re.sub(r"/\*.*?\*/|//[^\n]*", lambda m: "\n" * m[0].count("\n"),
                  text, flags=re.S)


def within(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def source_errors(text, module):
    errors = []
    clean = without_comments(text)
    for number, line in enumerate(clean.splitlines(), 1):
        match = re.match(r"\s*import\s+([\w.*]+)", line)
        if match and not any(within(match[1], p) for p in COMMON_IMPORTS[module]):
            errors.append(f"{number}: nonportable/unreviewed import {match[1]}")
        for prefix in PLATFORM_PACKAGES:
            # `platform` is also a legitimate presentation value (platform.name).
            suffix = r"\.(?:[A-Z]\w*|posix|darwin)\." if prefix == "platform" else r"\."
            if re.search(r"(?<![\w.])" + re.escape(prefix) + suffix, line):
                errors.append(f"{number}: platform package {prefix} belongs in an adapter")
    return errors


def project_dependencies(text):
    return set(re.findall(r"\b(?:project|findProject)\s*\(?\s*['\"]:([^'\"]+)['\"]",
                          without_comments(text)))


def check(root=ROOT):
    errors = []
    for module, expected in PROJECT_EDGES.items():
        build = root / module / "build.gradle"
        actual = project_dependencies(build.read_text(encoding="utf-8"))
        if actual != expected:
            errors.append(f"{module}: project dependencies {sorted(actual)} != reviewed {sorted(expected)}")
    for module in COMMON_IMPORTS:
        common = root / module / "src/commonMain/kotlin"
        files = sorted(common.rglob("*.kt"))
        if not files:
            errors.append(f"{module}: missing common sources")
        for path in files:
            errors.extend(f"{path.relative_to(root)}:{error}"
                          for error in source_errors(path.read_text(encoding="utf-8"), module))
        build = without_comments((root / module / "build.gradle").read_text(encoding="utf-8"))
        for target in ("androidTarget", "jvm", "iosArm64", "iosSimulatorArm64"):
            if not re.search(r"\b" + target + r"\s*[({]", build):
                errors.append(f"{module}: missing reviewed target {target}")
    return errors


if __name__ == "__main__":
    failures = check()
    if failures:
        print("\n".join(failures))
        raise SystemExit(1)
    print("Shared architecture boundaries OK (source checks, not native build evidence).")
