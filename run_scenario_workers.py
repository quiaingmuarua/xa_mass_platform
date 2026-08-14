import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT / "frontend"


def kernel_is_healthy() -> bool:
    try:
        with urllib.request.urlopen(
            "http://127.0.0.1:18080/health",
            timeout=1,
        ) as response:
            return (
                response.status == 200
                and json.load(response).get("status") == "ok"
            )
    except Exception:
        return False


owned_kernel = None
try:
    print("[1/3] Building frontend")
    if not (FRONTEND / ".env.local").exists():
        shutil.copyfile(
            FRONTEND / ".env.example",
            FRONTEND / ".env.local",
        )
    corepack = shutil.which(
        "corepack.cmd" if os.name == "nt" else "corepack"
    )
    pnpm = shutil.which("pnpm.cmd" if os.name == "nt" else "pnpm")
    if corepack:
        package_manager = [corepack, "pnpm"]
    elif pnpm:
        package_manager = [pnpm]
    else:
        raise RuntimeError("Neither corepack nor pnpm is available on PATH")
    subprocess.run(
        [*package_manager, "install", "--frozen-lockfile"],
        cwd=FRONTEND,
        check=True,
    )
    subprocess.run([*package_manager, "build"], cwd=FRONTEND, check=True)

    print("[2/3] Checking Python Kernel")
    if kernel_is_healthy():
        print("Reusing the healthy Kernel on 127.0.0.1:18080")
    else:
        python = os.environ.get(
            "XA_MASS_PYTHON_EXECUTABLE",
            sys.executable,
        )
        owned_kernel = subprocess.Popen(
            [
                python,
                "-m",
                "kernel_design.runtime_server",
                "--config",
                str(
                    ROOT
                    / "integrations"
                    / "worker-capability-rpc"
                    / "kernel-config.json"
                ),
            ],
            cwd=ROOT,
        )
        deadline = time.monotonic() + 30
        while not kernel_is_healthy():
            if owned_kernel.poll() is not None:
                raise RuntimeError(
                    "Python Kernel exited before becoming healthy"
                )
            if time.monotonic() >= deadline:
                raise RuntimeError(
                    "Python Kernel did not become healthy within 30 seconds"
                )
            time.sleep(0.25)

    print("[3/3] Starting Server, Adapter, and Scenario Workers")
    print("Open http://127.0.0.1:18082/runtime/workers")
    print("Tasks http://127.0.0.1:18082/runtime/tasks")
    print("Task Batches http://127.0.0.1:18082/runtime/task-batches")
    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    subprocess.run(
        [
            str(ROOT / gradle),
            ":server_jvm:bootRun",
            "--args=--spring.profiles.active=scenario-workers",
            "--console=plain",
        ],
        cwd=ROOT,
        check=True,
    )
except KeyboardInterrupt:
    pass
finally:
    if owned_kernel is not None and owned_kernel.poll() is None:
        print("Stopping the Python Kernel started by this script")
        owned_kernel.terminate()
        try:
            owned_kernel.wait(timeout=5)
        except subprocess.TimeoutExpired:
            owned_kernel.kill()
            owned_kernel.wait()
