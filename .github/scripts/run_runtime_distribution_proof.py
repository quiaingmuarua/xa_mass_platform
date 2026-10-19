#!/usr/bin/env python3
"""Unpack and prove the XA Mass Server Runtime outside the repository."""

from __future__ import annotations

import argparse
import json
import os
import re
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


# Explicit administrator (1000) from the packaged Server directory.
TASK_CALLER_HEADERS = {"X-XA-Dev-User": "MTAwMA", "X-XA-Dev-Role": "admin"}

def _request(
    method: str,
    url: str,
    body: object | None = None,
    *,
    timeout: float = 5.0,
    headers: dict[str, str] | None = None,
) -> tuple[int, bytes, str]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        method=method,
        headers={**({"Content-Type": "application/json"} if payload else {}), **(headers or {})},
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
        100,
    )
    if status != 200:
        raise RuntimeError(
            f"Worker preview for {worker_group_id} returned HTTP {status}"
        )
    workers = json.loads(payload).get("workers")
    if not isinstance(workers, list):
        raise RuntimeError(f"Worker preview for {worker_group_id} is invalid")
    return len(workers)


def _preview_tasks(base_url: str) -> list[dict[str, Any]]:
    status, payload, _ = _request(
        "POST",
        f"{base_url}/api/v1/runtime-view/tasks:preview",
        100,
        headers=TASK_CALLER_HEADERS,
    )
    if status != 200:
        raise RuntimeError(f"Task preview returned HTTP {status}")
    entries = json.loads(payload).get("entries")
    if not isinstance(entries, list) or any(
        not isinstance(entry, dict) for entry in entries
    ):
        raise RuntimeError("Task preview entries are invalid")
    return entries


def _preview_worker_groups(base_url: str) -> dict[str, Any]:
    status, payload, _ = _request(
        "POST",
        f"{base_url}/api/v1/runtime-view/worker-groups:preview",
        100,
    )
    if status != 200:
        raise RuntimeError(f"WorkerGroup preview returned HTTP {status}")
    preview = json.loads(payload)
    worker_groups = preview.get("workerGroups")
    if not isinstance(worker_groups, list) or any(
        not isinstance(worker_group, dict)
        for worker_group in worker_groups
    ):
        raise RuntimeError("WorkerGroup preview is invalid")
    return preview


def _prepare_rpc_task(base_url: str, project_id: str, worker_group_id: str) -> str:
    task_id = f"distribution-rpc-{worker_group_id}"
    status, payload, _ = _request(
        "POST",
        f"{base_url}/api/v1/tasks/{task_id}/rpc:prepare",
        {"projectId": project_id, "workerGroupId": worker_group_id},
        headers=TASK_CALLER_HEADERS,
    )
    if status != 200 or json.loads(payload) != {"taskId": task_id}:
        raise RuntimeError(f"RPC preparation for {worker_group_id} was not confirmed")
    status, payload, _ = _request(
        "GET", f"{base_url}/api/v1/projects/{project_id}/tasks/{task_id}",
        headers=TASK_CALLER_HEADERS,
    )
    if status != 200:
        raise RuntimeError(f"Prepared RPC Task point read returned HTTP {status}")
    entry = json.loads(payload)
    task = entry.get("task")
    if (
        entry.get("taskId") != task_id
        or not isinstance(task, dict)
        or task.get("taskId") != task_id
        or task.get("projectId") != project_id
        or task.get("workerGroupId") != worker_group_id
        or task.get("idleDisposition") != "PARK_WHEN_IDLE"
        or not isinstance(task.get("refill"), list)
        or entry.get("scoreBand") not in ("running-initial", "running_visible")
    ):
        raise RuntimeError("Prepared RPC Task identity or approved state is invalid")
    return task_id


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


def _clean_scope(
    repository_root: Path,
    redis_url: str,
    scope: str,
) -> None:
    cleanup = repository_root / ".github/scripts/cleanup_redis_test_scope.py"
    subprocess.run(
        [
            sys.executable,
            str(cleanup),
            "--redis-url",
            redis_url,
            "--scope",
            scope,
            "--best-effort",
        ],
        check=False,
    )


def _server_command(
    runtime_root: Path,
    profile: str,
    arguments: list[str],
) -> list[str]:
    manifest = json.loads(
        (runtime_root / "manifest.json").read_text(encoding="utf-8")
    )
    jar = (runtime_root / manifest["serverJar"]).resolve()
    return [
        os.environ.get("XA_MASS_JAVA_EXECUTABLE", "java"),
        "-jar",
        str(jar),
        f"--spring.profiles.active={profile}",
        "--spring.web.resources.static-locations="
        f"{(runtime_root / 'frontend/dist').resolve().as_uri()}/",
        *arguments,
    ]


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
    server_log_path = proof_root / "runtime-server.log"
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    command = _server_command(
        runtime_root,
        "default",
        [
            f"--spring.config.additional-location={(repository_root / 'integrations/worker_proof_support/platform-runtime.yaml').as_uri()}",
            f"--server.port={server_port}",
            "--xa.mass.worker-delivery.adapter.instances."
            f"scenario-websocket.listen-port={adapter_port}",
            "--xa.mass.worker-endpoints.endpoints.scenario-websocket.public-uri="
            f"ws://127.0.0.1:{adapter_port}/api/v1/worker-delivery/websocket",
        ],
    )
    flags: dict[str, Any] = {}
    if os.name == "nt":
        flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        flags["start_new_session"] = True
    with server_log_path.open("wb") as server_log:
        server_process = subprocess.Popen(
            command,
            cwd=runtime_root,
            env=environment,
            stdout=server_log,
            stderr=subprocess.STDOUT,
            **flags,
        )
        try:
            _wait_for_readiness(base_url, server_process)

            if _preview_tasks(base_url) != []:
                raise RuntimeError("Project bindings implicitly created Tasks")

            for worker_group_id in (
                "scenario-phone-number-workers",
                "scenario-string-utils-workers",
            ):
                if _preview_worker_count(base_url, worker_group_id) != 0:
                    raise RuntimeError(
                        "Packaged Server implicitly started Scenario Workers"
                    )
                _prepare_rpc_task(base_url, "scenario-workers", worker_group_id)

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

            status, api_reference, api_reference_type = _request(
                "GET", f"{base_url}/api-reference"
            )
            if (
                status != 200
                or api_reference_type != "text/html"
                or b'id="app"' not in api_reference
            ):
                raise RuntimeError(
                    "Static API Reference was not served by the Runtime archive"
                )
            status, openapi_payload, openapi_type = _request(
                "GET", f"{base_url}/reference/openapi.json"
            )
            if status != 200 or openapi_type != "application/json":
                raise RuntimeError(
                    "OpenAPI snapshot was not served by the Runtime archive"
                )
            openapi = json.loads(openapi_payload)
            if (
                openapi.get("openapi") != "3.1.0"
                or "servers" in openapi
                or not openapi.get("paths")
                or not all(
                    path.startswith("/api/v1/")
                    for path in openapi.get("paths", {})
                )
            ):
                raise RuntimeError("Packaged OpenAPI snapshot is invalid")

            status, reference_page, reference_type = _request(
                "GET", f"{base_url}/reference/error-codes"
            )
            if (
                status != 200
                or reference_type != "text/html"
                or b'id="app"' not in reference_page
            ):
                raise RuntimeError(
                    "Diagnostic Code Dictionary UI was not served by the Runtime archive"
                )
            status, dictionary_payload, dictionary_type = _request(
                "GET",
                f"{base_url}/reference/platform-diagnostic-codes.json",
            )
            if status != 200 or dictionary_type != "application/json":
                raise RuntimeError(
                    "Diagnostic Code Dictionary JSON was not served by the Runtime archive"
                )
            dictionary = json.loads(dictionary_payload)
            manifest = json.loads(
                (runtime_root / "manifest.json").read_text(encoding="utf-8")
            )
            if (
                dictionary.get("schemaVersion") != 1
                or dictionary.get("version") != manifest.get("version")
                or dictionary.get("gitCommit") != manifest.get("gitCommit")
                or [
                    owner.get("owner")
                    for owner in dictionary.get("owners", [])
                    if isinstance(owner, dict)
                ]
                != [
                    "server_jvm",
                    "transport:netty-adapter",
                    "transport:worker-core",
                ]
            ):
                raise RuntimeError(
                    "Packaged Diagnostic Code Dictionary metadata is invalid"
                )

            if (runtime_root / ".runtime/python-venv").exists():
                raise RuntimeError("Java-only Runtime created a Python venv")
            print(
                "Runtime distribution proof succeeded: "
                f"scope={scope}, fixture=platform-runtime, workers=0"
            )
        finally:
            _stop_process(server_process)


_AGENTFORGE_GROUPS = tuple(f"agentforge-{name}-workers" for name in (
    "detect-env", "instagram", "rcs", "telegram"
))


def _prove_agentforge_bindings(base_url: str) -> None:
    status, payload, _ = _request("GET", f"{base_url}/api/v1/projects", headers=TASK_CALLER_HEADERS)
    if status != 200 or json.loads(payload) != [
        {"projectId": "agentforge", "workerGroupIds": list(_AGENTFORGE_GROUPS), "workerGroupSource": "declared"}
    ]:
        raise RuntimeError("AgentForge directory must contain its declared Project, without default")
    path = f"{base_url}/api/v1/projects/agentforge/worker-groups"
    status, payload, _ = _request("GET", path, headers=TASK_CALLER_HEADERS)
    groups = json.loads(payload)
    if status != 200 or len(groups) != 4 or any(
        row.get("workerGroup") is not None or row.get("matching") != {
            "pools": [], "functions": [{"name": "workerId", "poolName": None}]
        } for row in groups
    ):
        raise RuntimeError("Unregistered Group bindings or directed-only capabilities differ")
    # Generic JVM test capability, not an Android business Handler claim.
    for group in _AGENTFORGE_GROUPS:
        status, payload, _ = _request("POST", f"{base_url}/api/v1/worker-groups/{group}:register",
            {"eventCodes": ["extension.worker.string.md5"]})
        if status != 200 or json.loads(payload).get("workerGroupId") != group:
            raise RuntimeError("Explicit AgentForge Group registration failed")
    status, payload, _ = _request("GET", path, headers=TASK_CALLER_HEADERS)
    if status != 200 or any(row.get("workerGroup") is None for row in json.loads(payload)):
        raise RuntimeError("Registered Group capabilities cannot be refreshed")
    if _preview_tasks(base_url) != []:
        raise RuntimeError("Registering Groups implicitly created Tasks")
    _prepare_rpc_task(base_url, "agentforge", _AGENTFORGE_GROUPS[0])
    status, payload, _ = _request("POST", f"{base_url}/api/v1/tasks", {
        "requestId": "distribution-agentforge-task",
        "projectId": "agentforge", "workerGroupId": _AGENTFORGE_GROUPS[0],
        "priority": 50, "maxRetryTimes": 3, "refill": []
    }, headers=TASK_CALLER_HEADERS)
    if status != 200 or not isinstance(json.loads(payload).get("taskId"), str):
        raise RuntimeError("Non-default finite creation failed")
    task_id = json.loads(payload)["taskId"]
    status, payload, _ = _request("GET", f"{base_url}/api/v1/projects/agentforge/tasks/{task_id}", headers=TASK_CALLER_HEADERS)
    task = json.loads(payload).get("task", {})
    if status != 200 or task.get("projectId") != "agentforge" or task.get("idleDisposition") != "CLOSE_WHEN_IDLE":
        raise RuntimeError("Non-default finite attribution differs")
    status, _, _ = _request("POST", f"{base_url}/api/v1/tasks/{task_id}/close", headers=TASK_CALLER_HEADERS)
    if status != 200:
        raise RuntimeError("Non-default finite close failed")
    for path in ("/task-workbench?project=agentforge&tab=rpc",
                 "/task-workbench/?project=agentforge&tab=finite&view=create",
                 "/runtime/tasks?project=agentforge"):
        status, payload, content_type = _request("GET", base_url + path)
        if status != 200 or content_type != "text/html" or b'id="app"' not in payload:
            raise RuntimeError("AgentForge workbench direct route unavailable")


def _prove_consumer_profile(
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
    environment["XA_MASS_REDIS_URL"] = redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    environment["SERVER_PORT"] = str(server_port)
    environment["XA_MASS_ADAPTER_PORT"] = str(adapter_port)
    flags: dict[str, Any] = {}
    if os.name == "nt":
        flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        flags["start_new_session"] = True
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            _server_command(runtime_root, "consumer", [
                "--xa.mass.project-assembly.projects[0].project-id=agentforge",
                *(f"--xa.mass.project-assembly.projects[0].worker-group-ids[{i}]={group}"
                  for i, group in enumerate(_AGENTFORGE_GROUPS))
            ]),
            cwd=runtime_root,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            **flags,
        )
        try:
            _wait_for_readiness(base_url, process)
            if _preview_tasks(base_url) != []:
                raise RuntimeError(
                    "Consumer Profile must not seed Tasks"
                )
            preview = _preview_worker_groups(base_url)
            if (
                preview.get("returnedCount") != 0
                or preview.get("workerGroups") != []
            ):
                raise RuntimeError(
                    "Consumer Profile must start with an empty WorkerGroup catalog"
                )
            with socket.create_connection(
                ("127.0.0.1", adapter_port), timeout=5
            ):
                pass
            _prove_agentforge_bindings(base_url)
            print(
                "Consumer Runtime Profile proof succeeded: "
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
            _prove_consumer_profile(
                runtime_root,
                arguments.redis_url,
                agentforge_scope,
                proof_root,
            )
        except Exception:
            for label, log_path in (
                ("Runtime Server", proof_root / "runtime-server.log"),
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
                repository_root,
                arguments.redis_url,
                scope,
            )
            _clean_scope(
                repository_root,
                arguments.redis_url,
                agentforge_scope,
            )
            if os.name == "nt":
                time.sleep(1)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
