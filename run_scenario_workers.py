import os
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT / "frontend"

try:
    print("[1/2] Building frontend")
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

    print("[2/2] Starting Server, Pacer child, Adapter, and Scenario Workers")
    print("Open http://127.0.0.1:18082/runtime/workers")
    print("Tasks http://127.0.0.1:18082/runtime/tasks")
    print("Task Batches http://127.0.0.1:18082/runtime/task-batches")
    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    subprocess.run(
        [
            str(ROOT / gradle),
            ":server_jvm:bootRun",
            "--args=--spring.profiles.active=scenario-workers "
            "--xa.mass.kernel-pacer.config-path="
            "integrations/worker-capability-rpc/kernel-config.json",
            "--console=plain",
        ],
        cwd=ROOT,
        check=True,
    )
except KeyboardInterrupt:
    pass
