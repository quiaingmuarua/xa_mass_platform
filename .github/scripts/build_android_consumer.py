#!/usr/bin/env python3
"""Build the real Android consumer outside the checkout from one pinned ZIP pair."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("xa_android_consumer", ROOT / "xa-android/run_consumer.py")
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


def main(arguments=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    consumer.add_archive_arguments(parser)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--runtime-api-base-url", default="http://127.0.0.1:18082")
    options = parser.parse_args(arguments)
    output = options.output_dir.resolve()
    if (output / "apk-provenance.json").exists():
        raise ValueError("APK output already has provenance; use a new output directory")
    with tempfile.TemporaryDirectory(prefix="xa-android-consumer-") as temporary:
        workspace = Path(temporary).resolve()
        if workspace.is_relative_to(ROOT):
            raise ValueError("Consumer proof must build outside the platform checkout")
        project = workspace / "consumer"
        consumer.copy_consumer(project)
        # Execute the copied entrypoint too: no parent-checkout imports are available to it.
        spec = importlib.util.spec_from_file_location("copied_consumer", project / "run_consumer.py")
        copied = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(copied)
        receipt = copied.prepare_options(options, workspace / "installation")
        wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper), "--no-daemon", "-p", str(project),
                   f"-PxaMassSdkRoot={receipt['sdkRoot']}", f"-PxaMassVersion={options.version}",
                   f"-PxaMassCommit={options.commit}", f"-PxaMassRuntimeApiBaseUrl={options.runtime_api_base_url}",
                   ":capabilities:testDebugUnitTest", ":worker-demo:testDebugUnitTest",
                   ":worker-demo:assembleDebug", ":worker-demo:assembleLab1",
                   ":worker-demo:assembleLab2", ":worker-demo:assembleLab3"]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", *command]
        result = subprocess.run(command, cwd=project)
        output.mkdir(parents=True, exist_ok=True)
        for module in ("capabilities", "worker-demo"):
            reports = project / module / "build/test-results/testDebugUnitTest"
            if reports.exists():
                shutil.copytree(reports, output / "test-results" / module)
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, command)
        consumption = json.loads((project / "build/sdk-consumption.json").read_text(encoding="utf-8"))
        apk_hashes = {}
        for variant in ("debug", "lab1", "lab2", "lab3"):
            apk = project / f"worker-demo/build/outputs/apk/{variant}/xa-mass-android-worker-demo-{variant}.apk"
            destination = output / apk.name
            shutil.copy2(apk, destination)
            apk_hashes[apk.name] = consumer.digest(destination)
        provenance = dict(receipt, runtimeApiBaseUrl=options.runtime_api_base_url,
                          projectId="default", caller={"userId": "1000", "role": "admin"},
                          profile="consumer", pacerPreset="DEFAULT", endpointManagerId=consumer.ADAPTER_ID,
                          independentBuild=True, sdkConsumption=consumption, apkSha256=apk_hashes)
        # Temporary paths are diagnostic only. Hashes, not those paths, identify the accepted bytes.
        (output / "apk-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"External Android consumer passed; APKs and provenance: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
