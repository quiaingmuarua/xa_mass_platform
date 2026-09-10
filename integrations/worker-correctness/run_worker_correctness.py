#!/usr/bin/env python3
"""Run the isolated 100-Worker correctness proof."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from integrations.worker_proof_support.scenario_inventory import (  # noqa: E402
    canonical_100_worker_world,
    materialize_inventory,
)


MODULE = ":integrations:worker-correctness"
RUNTIME_API = "http://127.0.0.1:18082"
LAB_API = "http://127.0.0.1:18086"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=ROOT / "build/worker-correctness-proof",
    )
    parser.add_argument("--maximum-wait-millis", type=int, default=120_000)
    parser.add_argument("--request-timeout-millis", type=int, default=120_000)
    options = parser.parse_args()

    output = options.output_root.resolve()
    if not output.is_relative_to(ROOT / "build") or output == ROOT / "build":
        raise ValueError("--output-root must be a child of the repository build directory")
    if output.exists():
        shutil.rmtree(output)
    evidence = output / "evidence"
    sandbox = output / "data" / "scenario-workers"
    evidence.mkdir(parents=True)
    (output / "private").mkdir()
    sandbox.parent.mkdir(parents=True)
    materialize_inventory(sandbox, canonical_100_worker_world())

    scope = "test_worker_correctness_" + uuid.uuid4().hex[:12]
    proof_id = "worker-correctness-" + scope[-12:]
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope

    _run([
        str(_gradle()),
        "--no-daemon",
        ":distribution:server:bootJar",
        ":scenario_workers_jvm:installDist",
        f"{MODULE}:installDist",
    ], environment)

    server: subprocess.Popen[str] | None = None
    host: subprocess.Popen[str] | None = None
    try:
        server = _start_server(output, environment)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_wait_millis,
            output / "runtime-server.log",
        )
        host = _start_host(output, sandbox, environment, "scenario-host-initial.log")
        _wait_http(
            f"{LAB_API}/lab/v1/workers",
            host,
            options.maximum_wait_millis,
            output / "scenario-host-initial.log",
        )

        initial = evidence / "worker-correctness-initial.json"
        _run_phase("initial", proof_id, sandbox, initial, None, options, environment)

        _run_live_properties(proof_id, sandbox, initial, output, host, server, options, environment)

        _stop_process(host, force=False)
        host = _start_host(output, sandbox, environment, "scenario-host-restart.log")
        _wait_http(
            f"{LAB_API}/lab/v1/workers",
            host,
            options.maximum_wait_millis,
            output / "scenario-host-restart.log",
        )
        _run_phase(
            "restart",
            proof_id,
            sandbox,
            evidence / "worker-correctness-restart.json",
            initial,
            options,
            environment,
        )
    finally:
        if host is not None:
            _stop_process(host, force=False)
        if server is not None:
            _stop_process(server, force=False)
        try:
            _run([
                sys.executable,
                str(ROOT / ".github/scripts/cleanup_redis_test_scope.py"),
                "--redis-url",
                options.redis_url,
                "--scope",
                scope,
                "--best-effort",
            ], environment)
        except BaseException as cleanup_error:
            print(f"Redis cleanup could not run: {cleanup_error}", file=sys.stderr)
    return 0


def _run_phase(
    phase: str,
    proof_id: str,
    sandbox: Path,
    evidence: Path,
    baseline: Path | None,
    options: argparse.Namespace,
    environment: dict[str, str],
) -> None:
    arguments = [
        f"--phase={phase}",
        f"--proof-id={proof_id}",
        f"--server-base-url={RUNTIME_API}",
        f"--lab-base-url={LAB_API}",
        f"--correctness-spec={ROOT / 'integrations/worker-correctness/correctness-spec.json'}",
        f"--scenario-worker-lab-root={sandbox}",
        f"--phone-seed-path={ROOT / 'integrations/worker-correctness/phone-seed.txt'}",
        f"--string-seed-path={ROOT / 'integrations/worker-correctness/string-seed.txt'}",
        f"--evidence-file={evidence}",
        f"--maximum-wait-millis={options.maximum_wait_millis}",
        f"--request-timeout-millis={options.request_timeout_millis}",
    ]
    if baseline is not None:
        arguments.append(f"--baseline-file={baseline}")
    classpath = ROOT / "integrations/worker-correctness/build/install/xa-mass-worker-correctness/lib/*"
    log_path = evidence.parent.parent / "private" / f"phase-{phase}.log"
    process = _start_process([
        "java", "-cp", str(classpath),
        "com.xa.mass.integration.workercorrectness.WorkerCorrectnessMain", *arguments,
    ], log_path, environment)
    try:
        timeout = 120 if phase == "live-properties" else 120 + options.maximum_wait_millis / 1000
        if process.wait(timeout=timeout) != 0:
            raise RuntimeError(f"Worker Correctness {phase} failed; inspect {evidence.name}")
    finally:
        _stop_process(process, force=True)


def _start_server(
    output: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    jars = [
        path for path in (ROOT / "distribution/server/build/libs").glob(
            "xa-mass-server-jvm-*.jar"
        ) if not path.name.endswith("-plain.jar")
    ]
    if not jars:
        raise RuntimeError("Runtime Server Boot JAR was not built")
    jar = max(jars, key=lambda path: path.stat().st_mtime_ns)
    return _start_process([
        "java",
        "-jar",
        str(jar),
        "--spring.profiles.active=scenario-workers",
        "--server.tomcat.accesslog.enabled=true",
        "--server.tomcat.accesslog.buffered=false",
        "--server.tomcat.accesslog.rotate=false",
        f"--server.tomcat.accesslog.directory={output / 'private'}",
        "--server.tomcat.accesslog.prefix=runtime-http",
        "--server.tomcat.accesslog.suffix=.log",
        "--server.tomcat.accesslog.pattern=%m %U %s",
    ], output / "runtime-server.log", environment)


def _start_host(
    output: Path,
    sandbox: Path,
    environment: dict[str, str],
    log_name: str,
) -> subprocess.Popen[str]:
    classpath = ROOT / "scenario_workers_jvm/build/install/xa-mass-scenario-workers/lib/*"
    return _start_process([
        "java",
        "-cp",
        str(classpath),
        "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
        f"--runtime-api-base-url={RUNTIME_API}",
        f"--sandbox-root={sandbox}",
        "--control-port=18086",
    ], output / log_name, environment)


def _start_process(
    command: list[str],
    log_path: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    log = log_path.open("w", encoding="utf-8", newline="\n")
    try:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            creationflags=(
                subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            ),
            start_new_session=os.name != "nt",
        )
    except BaseException:
        log.close()
        raise
    log.close()
    return process


def _stop_process(process: subprocess.Popen[str], *, force: bool) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        command = ["taskkill", "/PID", str(process.pid), "/T"]
        if force:
            command.append("/F")
        subprocess.run(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    elif force:
        os.killpg(process.pid, signal.SIGKILL)
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)


def _wait_http(
    url: str,
    process: subprocess.Popen[str],
    maximum_wait_millis: int,
    log_path: Path,
) -> None:
    deadline = time.monotonic() + maximum_wait_millis / 1000
    latest: BaseException | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"Process exited with {process.returncode}; log:\n{_tail(log_path)}"
            )
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if 200 <= response.status < 300:
                    return
        except (OSError, urllib.error.URLError) as error:
            latest = error
        time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {url}: {latest}; log:\n{_tail(log_path)}")


def _run(command: list[str], environment: dict[str, str]) -> None:
    subprocess.run(command, cwd=ROOT, env=environment, check=True)


def _tail(path: Path, maximum_lines: int = 80) -> str:
    if not path.exists():
        return "<missing>"
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace").splitlines()[
            -maximum_lines:
        ]
    )


def _gradle() -> Path:
    return ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def _prepare_counts(access: bytes) -> dict[str, int]:
    counts = {"prepare": 0, "prepareBatch": 0, "successful": 0}
    if not access or not access.endswith(b"\n"):
        raise RuntimeError("Missing or incomplete HTTP access records")
    for line in access.decode("utf-8").splitlines():
        record = re.fullmatch(r"([A-Z]+) (/\S*) ([1-5][0-9]{2})", line)
        if record is None:
            raise RuntimeError("Unexpected HTTP access record format")
        method, path, status = record.groups()
        route = re.fullmatch(r"/api/v1/worker-groups/[^/]+/workers:(prepare|prepare-batch)", path)
        if route is not None:
            name = "prepare" if route[1] == "prepare" else "prepareBatch"
            counts[name] += 1  # Include rejected/failed requests and every HTTP method.
            if method == "POST" and status == "200":
                counts["successful"] += 1
    return counts


def _read_access(path: Path) -> bytes:
    deadline = time.monotonic() + 2
    while True:
        data = path.read_bytes()
        if data.endswith(b"\n") or time.monotonic() >= deadline:
            _prepare_counts(data)
            return data
        time.sleep(0.01)


def _prepare_audit(before: bytes, after: bytes) -> dict[str, object]:
    initial = _prepare_counts(before)
    final = _prepare_counts(after)
    if initial["successful"] == 0:
        raise RuntimeError("HTTP access log did not observe initial Prepare")
    if not after.startswith(before):
        raise RuntimeError("HTTP access log was truncated or rotated")
    delta = {key: final[key] - initial[key] for key in ("prepare", "prepareBatch")}
    return {"initialPrepareObserved": True, "before": initial, "after": final, "requestDelta": delta}


def _control_records(sandbox: Path) -> dict[str, str]:
    controls = {}
    for role, group, index in (
        ("same-file-control", "scenario-string-utils-workers", 1),
        ("cross-group-control", "scenario-phone-number-workers", 0),
    ):
        records = (sandbox / group / "workers-000.jsonl").read_bytes().splitlines()
        if len(records) != 50:
            raise RuntimeError("Control inventory shape changed")
        controls[role] = hashlib.sha256(records[index]).hexdigest()
    return controls


def _run_live_properties(proof_id, sandbox, initial, output, host, server, options, environment):
    evidence_path = output / "evidence/worker-correctness-live-properties.json"
    access_path = output / "private/runtime-http.log"
    result = {"schemaVersion": 1, "proofId": proof_id, "phase": "live-properties", "status": "failed"}
    try:
        before = _read_access(access_path)
        if _prepare_counts(before)["successful"] == 0:
            raise RuntimeError("HTTP access log did not observe initial Prepare")
        controls = _control_records(sandbox)
        pid = host.pid
        if host.poll() is not None or server.poll() is not None:
            raise RuntimeError("Proof process exited before live Properties")
        _run_phase("live-properties", proof_id, sandbox, evidence_path, initial, options, environment)
        result = json.loads(evidence_path.read_text(encoding="utf-8"))
        audit = _prepare_audit(before, _read_access(access_path))
        result["prepareAudit"] = audit
        result["hostPidBefore"] = pid
        result["hostPidAfter"] = host.pid
        result["hostProcessUnchanged"] = host.pid == pid and host.poll() is None
        result["controlFileRecordsUnchanged"] = controls == _control_records(sandbox)
        if not result["hostProcessUnchanged"] or server.poll() is not None:
            raise RuntimeError("Proof process exited during live Properties")
        if not result["controlFileRecordsUnchanged"]:
            raise RuntimeError("Control file records changed")
        if audit["requestDelta"] != {"prepare": 0, "prepareBatch": 0}:
            raise RuntimeError("Prepare occurred during live Properties")
        if result.get("harnessStatus") != "succeeded":
            raise RuntimeError("Live Properties harness did not succeed")
        result["status"] = "succeeded"
    except Exception as error:
        if evidence_path.exists() and "harnessStatus" not in result:
            result = json.loads(evidence_path.read_text(encoding="utf-8"))
        result["status"] = "failed"
        result["runnerFailure"] = type(error).__name__
        raise
    finally:
        evidence_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
