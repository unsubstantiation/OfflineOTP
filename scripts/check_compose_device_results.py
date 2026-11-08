#!/usr/bin/env python3
"""Fail closed if selected Compose instrumentation tests were absent, skipped or unsuccessful."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

PACKAGE = "com.fairuniversal.offlineotp."
EXPECTED = {
    (PACKAGE + "ComposeVaultDeviceTest", "frameReplacementUsesCurrentActionsAndClearIsTerminal"),
    (PACKAGE + "ComposeVaultDeviceTest", "concealedFramesHaveNoProtectedTextOrArtworkRequests"),
    (PACKAGE + "ComposeVaultDeviceTest", "disablingThumbnailsSkipsTheHostArtworkLoader"),
    (PACKAGE + "IssuerArtworkDeviceTest", "bundledBrandsDecodeAndUnknownNamesUseTheSharedFallback"),
}


def check(directory: Path) -> list[str]:
    errors = []
    passed = set()
    files = sorted(directory.rglob("*.xml")) if directory.is_dir() else []
    if not files:
        return ["No instrumentation XML reports found; a build/type-check is not a device-test pass."]
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError):
            errors.append(f"Unreadable instrumentation report: {file.name}")
            continue
        for case in root.iter("testcase"):
            identity = (case.get("classname", ""), case.get("name", ""))
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                errors.append(f"Instrumentation case did not pass: {identity[0]}.{identity[1]}")
            elif identity in EXPECTED:
                passed.add(identity)
    for classname, method in sorted(EXPECTED - passed):
        errors.append(f"Missing passing device test: {classname}.{method}")
    return errors


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: check_compose_device_results.py <instrumentation-results-directory>")
    errors = check(Path(sys.argv[1]))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    print(f"All {len(EXPECTED)} selected Compose/artwork device tests executed successfully.")
