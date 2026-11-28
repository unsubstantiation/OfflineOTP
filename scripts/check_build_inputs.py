#!/usr/bin/env python3
"""Verify checked-in Gradle wrapper provenance and distribution settings.

Wrapper script/jar hashes are from Gradle's v9.7.0 release and the distribution
URL/hash match the pinned Gradle v9.7.0 distribution in gradle-wrapper.properties.
No downloads are executed by this check.
"""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
HASHES = {
    "gradle/wrapper/gradle-wrapper.jar": "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d",
    "gradlew": "e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750",
    "gradlew.bat": "4e193538b4ddccbee0c05d6383731b647945c146d16d8fe243b4ff716867896f",
}
DISTRIBUTION_URL = r"https\://services.gradle.org/distributions/gradle-9.7.0-all.zip"
DISTRIBUTION_HASH = "a9ecb5ac5c2ca40691e6527724d11d0b43b8c0a52825b77c09899f2a72d2d2bf"


def _read_wrapper_properties(root: Path, errors: list[str]) -> dict[str, str]:
    path = root / "gradle/wrapper/gradle-wrapper.properties"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        errors.append(f"Cannot inspect gradle/wrapper/gradle-wrapper.properties: {error}")
        return {}

    properties: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key in properties:
            errors.append(f"Duplicate wrapper property: {key}")
        properties[key] = value
    return properties


def check(root=ROOT):
    root = Path(root)
    errors = []
    for name, expected in HASHES.items():
        try:
            actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
        except OSError as error:
            errors.append(f"Cannot inspect {name}: {error}")
            continue
        if actual != expected:
            errors.append(f"Wrapper provenance mismatch: {name}")

    properties = _read_wrapper_properties(root, errors)
    if properties.get("distributionUrl") != DISTRIBUTION_URL:
        errors.append("Unexpected Gradle distribution URL")
    if properties.get("distributionSha256Sum") != DISTRIBUTION_HASH:
        errors.append("Gradle distribution checksum is missing or changed")
    if properties.get("validateDistributionUrl") != "true":
        errors.append("Gradle distribution URL validation disabled")

    return errors


def main():
    errors = check()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Wrapper hashes and distribution settings passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
