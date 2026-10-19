#!/usr/bin/env python3
"""Validate and identify the single ZIP pair consumed by all downstream jobs."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[2]
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
    runpy.run_path(str(ROOT / "distribution/server/src/test/python/verify_runtime_archive.py"))["verify"](
        options.runtime_archive, options.version)
    runpy.run_path(str(ROOT / "distribution/worker-sdk/src/test/python/verify_worker_sdk_archive.py"))["_verify_archive"](
        options.sdk_archive, options.version)
    options.runtime_sha256 = consumer.digest(options.runtime_archive)
    options.sdk_sha256 = consumer.digest(options.sdk_archive)
    receipt = consumer.prepare_options(options, options.output_dir / "installation")
    (options.output_dir / "candidate.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with Path(github_output).open("a", encoding="utf-8") as output:
            output.write(f"runtime_sha256={options.runtime_sha256}\nsdk_sha256={options.sdk_sha256}\n")
    print(f"Validated candidate: {options.version} commit={options.commit}")


if __name__ == "__main__":
    main()
