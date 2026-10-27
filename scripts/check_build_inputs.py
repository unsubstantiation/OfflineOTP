#!/usr/bin/env python3
"""Verify checked-in Gradle wrapper provenance and distribution settings.

Wrapper hashes are from Gradle's v8.13.0 release and the corresponding tagged
scripts: https://github.com/gradle/gradle-distributions/releases/tag/v8.13.0
No downloads are executed by this check.
"""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
HASHES = {
    "gradle/wrapper/gradle-wrapper.jar": "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f",
    "gradlew": "c76f16038b4e70b84ed0b3b9e08e82a8ecaf66ec0efdfdecdece92520fa3cecb",
    "gradlew.bat": "0f3ed8f03b50934cb8c48b15a470d5c20a30a5385825e48b55bcc8ea3d8f8e18",
}
DISTRIBUTION_HASH = "fba8464465835e74f7270bbf43d6d8a8d7709ab0a43ce1aa3323f73e9aa0c612"


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
    if properties.get("distributionUrl") != r"https\://services.gradle.org/distributions/gradle-8.13-all.zip":
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
