#!/usr/bin/env python3
"""Print concise failed Android instrumentation test names/messages from AGP XML results."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MAX_FAILURES = 12
MAX_MESSAGE_LENGTH = 300
MAX_TRACE_LENGTH = 600


def summarize(build_root: Path) -> list[str]:
    xml_files = sorted(
        path for path in build_root.rglob("*.xml")
        if "androidTest-results" in path.parts or "androidTests" in path.parts
    )
    if not xml_files:
        return ["No Android instrumentation JUnit XML was found under app/build"]

    lines: list[str] = []
    for path in xml_files:
        try:
            document = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        for testcase in document.iter("testcase"):
            for tag in ("failure", "error"):
                failure = testcase.find(tag)
                if failure is None:
                    continue
                name = ".".join(
                    part for part in (testcase.get("classname", ""), testcase.get("name", ""))
                    if part
                )
                message = " ".join(failure.get("message", "").split())
                trace = " ".join(" ".join(failure.itertext()).split())
                lines.append(f"{name}: {message[:MAX_MESSAGE_LENGTH]}")
                if trace and trace != message:
                    lines.append(f"  {trace[:MAX_TRACE_LENGTH]}")
                if sum(1 for line in lines if not line.startswith("  ")) >= MAX_FAILURES:
                    return lines

    if not lines:
        return ["No failing testcase entries were found in Android instrumentation XML"]
    return lines


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <app-build-dir>", file=sys.stderr)
        return 2
    for line in summarize(Path(sys.argv[1])):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
