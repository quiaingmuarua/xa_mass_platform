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


def _preview_worker_count(base_url: str, worker_group_id: str) -> int:
    status, payload, _ = _request(
        "POST",
        f"{base_url}/api/v1/runtime-view/worker-groups/"
        f"{worker_group_id}/workers:preview",
        {"sampleLimit": 100},
    )
    if status != 200:
        raise RuntimeError(
            f"Worker preview for {worker_group_id} returned HTTP {status}"
        )
    workers = json.loads(payload).get("workers")
    if not isinstance(workers, list):
        raise RuntimeError(f"Worker preview for {worker_group_id} is invalid")
    return len(workers)


def _wait_for_worker_fleet(
    base_url: str,
    host_process: subprocess.Popen[Any],
) -> None:
    expected = {
        "scenario-phone-number-workers": 10,
        "scenario-string-utils-workers": 10,
    }
    deadline = time.monotonic() + 90
    observed: dict[str, int] = {}
    while time.monotonic() < deadline:
        if host_process.poll() is not None:
            raise RuntimeError(
                "Scenario Worker Host exited before fleet readiness: "
                f"{host_process.returncode}"
            )
        observed = {
            group: _preview_worker_count(base_url, group)
            for group in expected
        }
        if observed == expected:
            return
        time.sleep(0.5)
    raise RuntimeError(
        f"Scenario Worker fleet readiness timed out: {observed}"
    )


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
    server_launcher = runtime_root / "bin/run-server.py"
    worker_launcher = runtime_root / "bin/run-scenario-workers.py"
    server_log_path = proof_root / "runtime-server.log"
    worker_log_path = proof_root / "scenario-worker-host.log"
    environment = os.environ.copy()
    environment.pop("PYTHONPATH", None)
    environment["PYTHONNOUSERSITE"] = "1"
    environment["XA_MASS_REDIS_URL"] = redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    command = [
        sys.executable,
        str(server_launcher),
        "--",
        f"--server.port={server_port}",
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
    worker_process: subprocess.Popen[Any] | None = None
    with server_log_path.open("wb") as server_log, worker_log_path.open(
        "wb"
    ) as worker_log:
        server_process = subprocess.Popen(
            command,
            cwd=proof_root,
            env=environment,
            stdout=server_log,
            stderr=subprocess.STDOUT,
            **flags,
        )
        try:
            _wait_for_readiness(base_url, server_process)

            for worker_group_id in (
                "scenario-phone-number-workers",
                "scenario-string-utils-workers",
            ):
                if _preview_worker_count(base_url, worker_group_id) != 0:
                    raise RuntimeError(
                        "Server launcher implicitly started Scenario Workers"
                    )

            worker_process = subprocess.Popen(
                [
                    sys.executable,
                    str(worker_launcher),
                    f"--runtime-api-base-url={base_url}",
                    "--sandbox-root="
                    f"{proof_root / 'data/scenario-workers'}",
                ],
                cwd=proof_root,
                env=environment,
                stdout=worker_log,
                stderr=subprocess.STDOUT,
                **flags,
            )
            _wait_for_worker_fleet(base_url, worker_process)

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

            _stop_process(worker_process)
            worker_process = None
            _wait_for_readiness(base_url, server_process)
        finally:
            if worker_process is not None:
                _stop_process(worker_process)
            _stop_process(server_process)


def _prove_agentforge_profile(
    runtime_root: Path,
    redis_url: str,
    scope: str,
    proof_root: Path,
) -> None:
    server_port = _free_port()
    adapter_port = _free_port()
    while adapter_port == server_port:
        adapter_port = _free_port()
    base_url = f"http://127.0.0.1:{server_port}"
    log_path = proof_root / "agentforge-server.log"
    environment = os.environ.copy()
    environment.pop("PYTHONPATH", None)
    environment["PYTHONNOUSERSITE"] = "1"
    environment["XA_MASS_REDIS_URL"] = redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    environment["XA_MASS_AGENTFORGE_SERVER_PORT"] = str(server_port)
    environment["XA_MASS_AGENTFORGE_ADAPTER_PORT"] = str(adapter_port)
    flags: dict[str, Any] = {}
    if os.name == "nt":
        flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        flags["start_new_session"] = True
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            [
                sys.executable,
                str(runtime_root / "bin/run-server.py"),
                "--profile",
                "agentforge",
            ],
            cwd=proof_root,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            **flags,
        )
        try:
            _wait_for_readiness(base_url, process)
            status, configured_payload, _ = _request(
                "GET", f"{base_url}/api/v1/runtime-view/configured-resources"
            )
            if status != 200 or json.loads(configured_payload).get("entries") != []:
                raise RuntimeError(
                    "AgentForge Profile must not seed configured resources"
                )
            status, preview_payload, _ = _request(
                "POST",
                f"{base_url}/api/v1/runtime-view/worker-groups:preview",
                {"sampleLimit": 100},
            )
            preview = json.loads(preview_payload)
            if (
                status != 200
                or preview.get("returnedCount") != 0
                or preview.get("workerGroups") != []
            ):
                raise RuntimeError(
                    "AgentForge Profile must start with an empty WorkerGroup catalog"
                )
            with socket.create_connection(
                ("127.0.0.1", adapter_port), timeout=5
            ):
                pass
            print(
                "AgentForge Runtime Profile proof succeeded: "
                f"scope={scope}, serverPort={server_port}, "
                f"adapterPort={adapter_port}"
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
    agentforge_scope = f"{scope}_agentforge"

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
            _prove_agentforge_profile(
                runtime_root,
                arguments.redis_url,
                agentforge_scope,
                proof_root,
            )
        except Exception:
            for label, log_path in (
                ("Runtime Server", proof_root / "runtime-server.log"),
                (
                    "Scenario Worker Host",
                    proof_root / "scenario-worker-host.log",
                ),
                (
                    "AgentForge Server",
                    proof_root / "agentforge-server.log",
                ),
            ):
                if log_path.is_file():
                    print(f"--- {label} log tail ---", file=sys.stderr)
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
            _clean_scope(
                runtime_root,
                repository_root,
                arguments.redis_url,
                agentforge_scope,
            )
            if os.name == "nt":
                time.sleep(1)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
