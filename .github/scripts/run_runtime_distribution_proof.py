#!/usr/bin/env python3
"""Unpack and prove the XA Mass Server Runtime outside the repository."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any


_SCOPE = re.compile(r"test_[a-z0-9_]+")


def _safe_extract(archive: Path, destination: Path) -> Path:
    with zipfile.ZipFile(archive) as runtime:
        roots: set[str] = set()
        for entry in runtime.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or not path.parts or ".." in path.parts:
                raise ValueError(f"unsafe Runtime ZIP entry: {entry.filename}")
            roots.add(path.parts[0])
            file_type = (entry.external_attr >> 16) & 0o170000
            if file_type == stat.S_IFLNK:
                raise ValueError(f"Runtime ZIP contains a symlink: {entry.filename}")
        if len(roots) != 1:
            raise ValueError("Runtime ZIP must have one versioned root")
        runtime.extractall(destination)
    root = destination / roots.pop()
    if not root.is_dir():
        raise ValueError("Runtime ZIP root was not extracted")
    return root


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _request(
    method: str,
    url: str,
    body: dict[str, Any] | None = None,
    *,
    timeout: float = 5.0,
) -> tuple[int, bytes, str]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        method=method,
        headers={"Content-Type": "application/json"} if payload else {},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return (
                response.status,
                response.read(),
                response.headers.get_content_type(),
            )
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers.get_content_type()


def _wait_for_readiness(base_url: str, process: subprocess.Popen[Any]) -> None:
    deadline = time.monotonic() + 120
    last_error = "not attempted"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"Runtime launcher exited before readiness: {process.returncode}"
            )
        try:
            status, payload, _ = _request(
                "GET", f"{base_url}/actuator/health/readiness"
            )
            if status == 200 and json.loads(payload).get("status") == "UP":
                return
            last_error = f"HTTP {status}"
        except (OSError, ValueError, urllib.error.URLError) as error:
            last_error = str(error)
        time.sleep(0.5)
    raise RuntimeError(f"Runtime readiness timed out: {last_error}")


def _stop_process(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    else:
        os.killpg(process.pid, signal.SIGINT)
    try:
        process.wait(timeout=25)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/F", "/PID", str(process.pid), "/T"],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            process.kill()
        process.wait(timeout=5)


def _venv_python(runtime_root: Path) -> Path:
    return runtime_root / ".runtime/python-venv" / (
        "Scripts/python.exe" if os.name == "nt" else "bin/python"
    )


def _clean_scope(
    runtime_root: Path,
    repository_root: Path,
    redis_url: str,
    scope: str,
) -> None:
    python = _venv_python(runtime_root)
    cleanup = repository_root / ".github/scripts/cleanup_redis_test_scope.py"
    if python.is_file():
        subprocess.run(
            [
                str(python),
                str(cleanup),
                "--redis-url",
                redis_url,
                "--scope",
                scope,
            ],
            check=True,
        )


def _prove_runtime(
    runtime_root: Path,
    repository_root: Path,
    redis_url: str,
    scope: str,
    proof_root: Path,
) -> None:
    server_port = _free_port()
    adapter_port = _free_port()
    while adapter_port == server_port:
        adapter_port = _free_port()
    base_url = f"http://127.0.0.1:{server_port}"
    launcher = runtime_root / "bin/run-server.py"
    log_path = proof_root / "runtime-server.log"
    environment = os.environ.copy()
    environment.pop("PYTHONPATH", None)
    environment["PYTHONNOUSERSITE"] = "1"
    environment["XA_MASS_REDIS_URL"] = redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    command = [
        sys.executable,
        str(launcher),
        "--",
        f"--server.port={server_port}",
        f"--xa.mass.worker-assembly.runtime-api-base-url={base_url}",
        "--xa.mass.worker-assembly.sandbox-root="
        f"{proof_root / 'data/scenario-workers'}",
        "--xa.mass.task-batch.root=" f"{proof_root / 'data/rpc-task'}",
        "--xa.mass.worker-delivery.adapter.instances."
        f"scenario-websocket.listen-port={adapter_port}",
        "--xa.mass.worker-binding.endpoints.scenario-websocket.public-uri="
        f"ws://127.0.0.1:{adapter_port}/api/v1/worker-delivery/websocket",
    ]
    flags: dict[str, Any] = {}
    if os.name == "nt":
        flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        flags["start_new_session"] = True
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            command,
            cwd=proof_root,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            **flags,
        )
        try:
            _wait_for_readiness(base_url, process)

            status, scalar, scalar_type = _request("GET", f"{base_url}/scalar")
            if status != 200 or scalar_type != "text/html":
                raise RuntimeError("Scalar was not served by the Runtime archive")
            status, frontend, frontend_type = _request(
                "GET", f"{base_url}/runtime/workers"
            )
            if (
                status != 200
                or frontend_type != "text/html"
                or b'id="app"' not in frontend
            ):
                raise RuntimeError("Frontend was not served by the Runtime archive")

            status, configured_payload, _ = _request(
                "GET", f"{base_url}/api/v1/runtime-view/configured-resources"
            )
            if status != 200:
                raise RuntimeError("Configured Runtime resources were unavailable")
            configured = json.loads(configured_payload)
            string_entry = next(
                entry
                for entry in configured.get("entries", [])
                if entry.get("workerGroupId")
                == "scenario-string-utils-workers"
            )
            task_id = string_entry.get("taskId")
            if not isinstance(task_id, str) or not task_id:
                raise RuntimeError("Scenario String Task ID was unavailable")

            message_id = f"distribution-proof-{uuid.uuid4().hex}"
            status, call_payload, _ = _request(
                "POST",
                f"{base_url}/api/v1/tasks/{task_id}/items:call",
                {
                    "items": [
                        {
                            "messageId": message_id,
                            "eventCode": "extension.worker.string.md5",
                            "payload": {"value": "distribution-proof"},
                            "allocationRule": {},
                        }
                    ],
                    "waitTimeoutMillis": 60_000,
                },
                timeout=70,
            )
            if status != 200:
                raise RuntimeError(f"Packaged Task Call returned HTTP {status}")
            result = json.loads(call_payload).get("results", {}).get(message_id)
            if not isinstance(result, dict) or result.get("status") != "succeeded":
                raise RuntimeError("Packaged Task Call was not observed")

            marker = runtime_root / ".runtime/python-venv/.xa-mass-runtime.json"
            if not marker.is_file():
                raise RuntimeError("Offline Runtime venv marker was not created")
            print(
                "Runtime distribution proof succeeded: "
                f"scope={scope}, taskId={task_id}, messageId={message_id}"
            )
        finally:
            _stop_process(process)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--scope")
    arguments = parser.parse_args()
    scope = arguments.scope or f"test_distribution_{uuid.uuid4().hex}"
    if _SCOPE.fullmatch(scope) is None:
        parser.error("--scope must be an exact lowercase test_* scope")

    repository_root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="xa-mass-runtime-proof-") as temporary:
        proof_root = Path(temporary)
        runtime_root = _safe_extract(arguments.archive.resolve(), proof_root)
        try:
            _prove_runtime(
                runtime_root,
                repository_root,
                arguments.redis_url,
                scope,
                proof_root,
            )
        except Exception:
            log_path = proof_root / "runtime-server.log"
            if log_path.is_file():
                print("--- Runtime Server log tail ---", file=sys.stderr)
                print(
                    "\n".join(
                        log_path.read_text(
                            encoding="utf-8", errors="replace"
                        ).splitlines()[-200:]
                    ),
                    file=sys.stderr,
                )
            raise
        finally:
            _clean_scope(
                runtime_root,
                repository_root,
                arguments.redis_url,
                scope,
            )
            if os.name == "nt":
                time.sleep(1)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
