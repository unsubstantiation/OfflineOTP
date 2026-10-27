#!/usr/bin/env python3
"""Print concise failed Android instrumentation test names/messages from AGP XML results."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MAX_FAILURES = 40
MAX_MESSAGE_LENGTH = 300
MAX_TRACE_LENGTH = 600



def _xml_files(build_root: Path) -> list[Path]:
    return sorted(
        path for path in build_root.rglob("*.xml")
        if "androidTest-results" in path.parts or "androidTests" in path.parts
    )


def _failures(xml_files: list[Path]):
    """Yield (test name, message, trace) for every failed testcase in the JUnit XML."""
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
                yield name, message, trace


NO_RESULTS = "No Android instrumentation JUnit XML was found under app/build"


def summarize(build_root: Path) -> list[str]:
    xml_files = _xml_files(build_root)
    if not xml_files:
        return [NO_RESULTS]

    lines: list[str] = []
    seen = 0
    for name, message, trace in _failures(xml_files):
        lines.append(f"{name}: {message[:MAX_MESSAGE_LENGTH]}")
        if trace and trace != message:
            lines.append(f"  {trace[:MAX_TRACE_LENGTH]}")
        seen += 1
        if seen >= MAX_FAILURES:
            break

    if not lines:
        return ["No failing testcase entries were found in Android instrumentation XML"]
    return lines


def usage(program: str) -> int:
    print(f"usage: {program} <app-build-dir>", file=sys.stderr)
    return 2


def main() -> int:
    arguments = sys.argv[1:]
    if len(arguments) != 1:
        return usage(Path(sys.argv[0]).name)

    build_root = Path(arguments[0])
    for line in summarize(build_root):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
