from __future__ import annotations

import argparse
import glob
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Summarize Gradle JUnit XML reports for GitHub Actions."
    )
    parser.add_argument("--label", required=True)
    parser.add_argument("patterns", nargs="+")
    args = parser.parse_args()

    report_paths = sorted(
        {
            Path(match)
            for pattern in args.patterns
            for match in glob.glob(pattern, recursive=True)
        }
    )
    totals = {
        "tests": 0,
        "skipped": 0,
        "failures": 0,
        "errors": 0,
    }
    for report_path in report_paths:
        suite = ET.parse(report_path).getroot()
        for name in totals:
            totals[name] += int(suite.attrib.get(name, "0"))

    if report_paths:
        result = ", ".join(
            f"{name}={value}" for name, value in totals.items()
        )
    else:
        result = "no JUnit XML reports were produced"
    line = f"- **{args.label}:** {result}"
    print(line)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as summary:
            summary.write(line + "\n")


if __name__ == "__main__":
    main()
