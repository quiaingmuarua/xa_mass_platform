#!/usr/bin/env python3
"""Start one local Runtime with optional Java and Android Workers."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "distribution/server"))
from local_runtime import LocalRun, child_environment, parse_arguments, resolve


def build(settings):
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    version = re.search(r"\.orElse\('([^']+)'\)", (ROOT / "build.gradle").read_text(encoding="utf-8")).group(1)
    tasks = [":distribution:server:stagePreviewServer", ":distribution:server:stagePreviewFrontend"]
    if settings["java_config"]:
        tasks.append(":distribution:server:stagePreviewHost")
    if settings["android_count"]:
        version = version.split("-")[0]
        tasks += [":distribution:server:distZip", ":distribution:worker-sdk:distZip"]
    subprocess.run([str(gradle), f"-PxaMassVersion={version}", *tasks, "--console=plain"],
                   cwd=ROOT, env=child_environment(), check=True)
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
        print(f"Local startup failed: {error}", file=sys.stderr)
        return 1
    finally:
        if run:
            run.close()


if __name__ == "__main__":
    raise SystemExit(main())
