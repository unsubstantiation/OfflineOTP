#!/usr/bin/env python3
"""Print concise failed Android instrumentation test names/messages from AGP XML results.

Two output modes:

* the default plain text report, written to ``reports/emulator/test-failures.txt``;
* ``--github``, which emits one ``::error`` workflow command per failing test. A single
  command line is truncated by the Actions UI (4096 characters), so bundling every failure
  into one line hides all but the first few; one annotation per failure survives intact.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MAX_FAILURES = 40
MAX_MESSAGE_LENGTH = 300
MAX_TRACE_LENGTH = 600
# One workflow command must stay well below the 4096-character annotation limit once the
# title, escaping and the surrounding text are added.
MAX_ANNOTATION_LENGTH = 3_500
GITHUB_TITLE = "Instrumentation test failure"


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


def _escape_workflow_command(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotate(build_root: Path) -> list[str]:
    """One ``::error`` command per failing test so the Actions UI can show every failure."""
    xml_files = _xml_files(build_root)
    if not xml_files:
        return [f"::error title={GITHUB_TITLE}::{NO_RESULTS}"]

    annotations: list[str] = []
    seen = 0
    for name, message, trace in _failures(xml_files):
        detail = f"{name}: {message}"
        if trace and trace != message:
            detail = f"{detail} | {trace}"
        detail = detail[:MAX_ANNOTATION_LENGTH]
        annotations.append(
            f"::error title={GITHUB_TITLE}::{_escape_workflow_command(detail)}"
        )
        seen += 1
        if seen >= MAX_FAILURES:
            annotations.append(
                f"::error title={GITHUB_TITLE}::more than {MAX_FAILURES} failing tests; "
                "see the instrumentation logs artifact for the rest"
            )
            break

    if not annotations:
        annotations.append(
            f"::error title={GITHUB_TITLE}::no Android instrumentation JUnit XML was found"
        )
    return annotations


def usage(program: str) -> int:
    print(f"usage: {program} [--github] <app-build-dir>", file=sys.stderr)
    return 2


def main() -> int:
    arguments = sys.argv[1:]
    github = False
    if arguments and arguments[0] == "--github":
        github = True
        arguments = arguments[1:]
    if len(arguments) != 1:
        return usage(Path(sys.argv[0]).name)

    build_root = Path(arguments[0])
    for line in annotate(build_root) if github else summarize(build_root):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
