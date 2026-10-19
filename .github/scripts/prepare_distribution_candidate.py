#!/usr/bin/env python3
"""Validate and identify the single ZIP pair consumed by all downstream jobs."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from distribution.archive_tools import verify_runtime, verify_sdk
SPEC = importlib.util.spec_from_file_location("xa_android_consumer", ROOT / "xa-android/run_consumer.py")
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


def main(arguments=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-archive", type=Path, required=True)
    parser.add_argument("--sdk-archive", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    options = parser.parse_args(arguments)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    consumer.require(not dirty and head == options.commit, "Candidate must come from the expected clean commit")
    verify_runtime(
        options.runtime_archive, options.version)
    verify_sdk(
        options.sdk_archive, options.version)
    options.runtime_sha256 = consumer.digest(options.runtime_archive)
    options.sdk_sha256 = consumer.digest(options.sdk_archive)
    receipt = consumer.prepare_options(options, options.output_dir / "installation")
    receipt["buildUrl"] = (os.environ["GITHUB_SERVER_URL"] + "/" + os.environ["GITHUB_REPOSITORY"]
                            + "/actions/runs/" + os.environ["GITHUB_RUN_ID"]
                            if os.environ.get("GITHUB_RUN_ID") else None)
    (options.output_dir / "candidate.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with Path(github_output).open("a", encoding="utf-8") as output:
            output.write(f"runtime_sha256={options.runtime_sha256}\nsdk_sha256={options.sdk_sha256}\n")
    print(f"Validated candidate: {options.version} commit={options.commit}")


if __name__ == "__main__":
    main()
