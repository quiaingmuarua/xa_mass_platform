#!/usr/bin/env python3
"""Start one local Runtime with optional Java and Android Workers."""
from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "distribution/server"))
from local_runtime import LocalRun, child_environment, digest, parse_arguments, resolve

CANDIDATE_CACHE = ROOT / "build/local-runtime-candidate.json"


def source_signature():
    """Conservative cache key; no branch-name/version-only reuse of candidate ZIPs."""
    fingerprint = hashlib.sha256(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT))
    names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode("utf-8").split("\0")
    names += [p.relative_to(ROOT).as_posix() for p in (ROOT / "frontend").glob(".env*") if p.is_file()]
    for name in sorted(set(names) - {""}):
        path = ROOT / name
        if path.is_file():
            fingerprint.update(name.encode("utf-8") + b"\0" + bytes.fromhex(digest(path)))
    fingerprint.update(json.dumps({key: os.environ.get(key) for key in ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT")}, sort_keys=True).encode())
    return fingerprint.hexdigest()


def candidate_archives(version):
    return {
        "runtime": ROOT / f"distribution/server/build/distributions/xa-mass-server-runtime-{version}.zip",
        "sdk": ROOT / f"distribution/worker-sdk/build/distributions/xa-mass-worker-sdk-{version}.zip",
    }


def candidate_current(signature, version):
    try:
        record = json.loads(CANDIDATE_CACHE.read_text(encoding="utf-8"))
        return record["source"] == signature and record["version"] == version and all(
            path.is_file() and digest(path) == record["sha256"][name]
            for name, path in candidate_archives(version).items())
    except (OSError, ValueError, KeyError, TypeError):
        return False


def remember_candidate(signature, version):
    CANDIDATE_CACHE.parent.mkdir(parents=True, exist_ok=True)
    record = {"source": signature, "version": version,
              "sha256": {name: digest(path) for name, path in candidate_archives(version).items()}}
    temporary = CANDIDATE_CACHE.with_suffix(".tmp")
    temporary.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    temporary.replace(CANDIDATE_CACHE)


def build(settings):
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    version = re.search(r"\.orElse\('([^']+)'\)", (ROOT / "build.gradle").read_text(encoding="utf-8")).group(1)
    tasks = [":distribution:server:stagePreviewServer", ":distribution:server:stagePreviewFrontend"]
    if settings["java_config"]:
        tasks.append(":distribution:server:stagePreviewHost")
    if settings["android_count"]:
        version = version.split("-")[0]
        signature = source_signature()
        rebuild_candidate = not candidate_current(signature, version)
        if rebuild_candidate:
            tasks += [":distribution:server:distZip", ":distribution:worker-sdk:distZip"]
        else:
            print("Reusing checked Runtime/SDK candidate ZIPs for unchanged source", flush=True)
    subprocess.run([str(gradle), f"-PxaMassVersion={version}", *tasks, "--console=plain"],
                   cwd=ROOT, env=child_environment(), check=True)
    if settings["android_count"] and rebuild_candidate:
        remember_candidate(signature, version)
    return version


def main(arguments=None):
    run = None
    try:
        args = parse_arguments(arguments)
        settings = resolve(args)
        run = LocalRun(settings)
        run.preflight()
        print(json.dumps({
            "profile": settings["profile"], "runtime": run.url,
            "adapterPort": settings["adapter_port"], "scope": run.scope,
            "workDir": str(settings["work"]), "javaConfiguration": str(settings["java_file"]) if settings["java_file"] else None,
            "runtimeConfiguration": str(settings["runtime_config"]) if settings["runtime_config"] else None,
            "groups": str(settings["groups_file"]) if settings["groups_file"] else None,
            "androidCount": settings["android_count"], "temporaryScope": settings["temporary_scope"],
            "javaCounts": settings["java_config"].get("startupPlan", {}).get("workerCounts") if settings["java_config"] else {},
            "androidDevice": run.android.serial if run.android else None,
            "androidPackages": [package for package, _, _ in run.android.apps] if run.android else [],
        }, ensure_ascii=False, indent=2), flush=True)
        version = build(settings)
        if run.android:
            run.android.prepare(ROOT, version)
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        run.start()
        print(f"Ready: {run.url} ; logs: {run.output}", flush=True)
        while True:
            run.check()
            time.sleep(1)
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Local runtime failed: {error}", file=sys.stderr)
        return 1
    finally:
        if run:
            run.close()


if __name__ == "__main__":
    raise SystemExit(main())
