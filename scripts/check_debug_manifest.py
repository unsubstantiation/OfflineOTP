#!/usr/bin/env python3
"""Check limited safety invariants in an APK's *packaged* debug manifest.

Input is text XML from `apkanalyzer manifest print`, not a source manifest.
This is not a malware scanner or a substitute for the full release policy.
"""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
POLICY = Path(__file__).with_name("manifest-policy.json")


def check_manifest(path, policy=None):
    if policy is None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
    root = ET.parse(path).getroot()
    errors = []
    if root.tag != "manifest":
        return ["Not an Android manifest"]

    base_package = policy["package"]
    package = base_package + ".dev"
    if root.get("package") != package:
        errors.append(f"Unexpected debug application ID: {root.get('package')}")

    sdk = root.find("uses-sdk")
    if sdk is None:
        errors.append("Packaged manifest has no uses-sdk")
    else:
        for attribute, expected in (("minSdkVersion", policy["min_sdk"]),
                                    ("targetSdkVersion", policy["target_sdk"])):
            if sdk.get(ANDROID + attribute) != str(expected):
                errors.append(f"Unexpected {attribute}: {sdk.get(ANDROID + attribute)}")

    # Application-ID-owned permissions are renamed by the debug variant's .dev suffix.
    # Component class names and legacy intent action names do not change with the suffix.
    owned = {base_package + ".permission.CONTROL_VAULT",
             base_package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    allowed_permissions = {
        package + permission[len(base_package):] if permission in owned else permission
        for permission in policy["allowed_permissions"]
    }
    for element in root:
        # This also catches uses-permission-sdk-23 and similar variant elements.
        if element.tag.startswith("uses-permission"):
            name = element.get(ANDROID + "name", "")
            # The offline invariant must survive even an accidental allowlist edit.
            if name == "android.permission.INTERNET" or name not in allowed_permissions:
                errors.append(f"Unreviewed permission: {name}")

    applications = root.findall("application")
    if len(applications) != 1:
        return errors + ["Expected exactly one application"]
    app = applications[0]
    if app.get(ANDROID + "debuggable") != "true":
        errors.append("Expected a debuggable APK for the debug artifact")
    if app.get(ANDROID + "allowBackup") != "false":
        errors.append("Platform backup/transfer is not disabled")
    if app.get(ANDROID + "backupAgent") is not None:
        errors.append("Unexpected platform backup agent")
    if app.get(ANDROID + "testOnly", "false") != "false":
        errors.append("Unexpected testOnly flag")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        errors = check_manifest(args.manifest)
    except (OSError, ValueError, ET.ParseError) as error:
        errors = [f"Cannot inspect manifest: {error}"]
    if errors:
        for error in errors:
            print(f"{args.manifest}: {error}", file=sys.stderr)
        return 1
    print(f"{args.manifest}: debug manifest checks passed (not a malware scan)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
