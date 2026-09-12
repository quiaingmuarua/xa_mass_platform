#!/usr/bin/env python3
"""Run the fixed dynamic matching proof using independent Server, Host and Harness processes."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from integrations.worker_proof_support.scenario_inventory import (  # noqa: E402
    canonical_1000_worker_world, materialize_inventory, STRING_GROUP, PHONE_GROUP,
)

EVENT = "extension.worker.lab.execution-witness"
MODULE = ":integrations:worker-dynamic-matching"


def prepare_counts(data: bytes) -> dict[str, int]:
    if not data or not data.endswith(b"\n"):
        raise ValueError("Missing or incomplete HTTP access records")
    counts = {"prepare": 0, "prepareBatch": 0, "successful": 0}
    for line in data.decode("utf-8").splitlines():
        record = re.fullmatch(r"([A-Z]+) (/\S*) ([1-5][0-9]{2})", line)
        if record is None:
            raise ValueError("Invalid access record")
        method, path, status = record.groups()
        route = re.fullmatch(r"/api/v1/worker-groups/[^/]+/workers:(prepare|prepare-batch)", path)
        if route:
            counts["prepare" if route[1] == "prepare" else "prepareBatch"] += 1
            counts["successful"] += int(method == "POST" and status == "200")
    return counts


def read_access(path: Path) -> bytes:
    end = time.monotonic() + 2
    while True:
        data = path.read_bytes()
        if data.endswith(b"\n") or time.monotonic() >= end:
            prepare_counts(data)
            return data
        time.sleep(.01)


def audit_prepare(before: bytes, after: bytes) -> dict:
    first, last = prepare_counts(before), prepare_counts(after)
    if not first["successful"] or not after.startswith(before):
        raise ValueError("Initial Prepare missing or access log rotated")
    delta = {key: last[key] - first[key] for key in ("prepare", "prepareBatch")}
    if any(delta.values()):
        raise ValueError("Prepare occurred during dynamic matching")
    return {"initialPrepareObserved": True, "requestDelta": delta}


def materialize(root: Path) -> list[dict]:
    world = canonical_1000_worker_world()
    for group, rows in world.items():
        for index, row in enumerate(rows):
            if group == STRING_GROUP:
                slot = index % 100
                row.update(proofPool="A" if slot < 40 or slot >= 80 else "B",
                           proofTarget="yes" if slot >= 80 else "no")
            row["emptySentinel"] = ""
    keys = materialize_inventory(root, world)
    spec = []
    for group, coordinates in keys.items():
        files = {f"workers-{index:03d}.jsonl": (root / group / f"workers-{index:03d}.jsonl")
                 .read_text(encoding="utf-8").splitlines() for index in range(5)}
        for key in coordinates:
            filename, line = key.rsplit(":", 1)
            spec.append({"group": group, "key": key,
                         "properties": json.loads(files[filename][int(line) - 1])["workerProperties"]})
    return spec


def control_records(root: Path) -> dict[str, str]:
    result = {}
    for group in (STRING_GROUP, PHONE_GROUP):
        paths = sorted((root / group).glob("*.jsonl"))
        if [p.name for p in paths] != [f"workers-{index:03d}.jsonl" for index in range(5)]:
            raise ValueError("Inventory shape changed")
        for path in paths:
            lines = path.read_bytes().splitlines()
            if len(lines) != 100:
                raise ValueError("Inventory shape changed")
            for index, line in enumerate(lines):
                if group == PHONE_GROUP or index < 80:
                    result[f"{group}/{path.name}:{index + 1}"] = hashlib.sha256(line).hexdigest()
    return result


def start(command: list[str], log: Path, environment: dict[str, str]) -> subprocess.Popen:
    with log.open("w", encoding="utf-8") as stream:
        return subprocess.Popen(command, cwd=ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT,
                                creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if os.name == "nt" else 0,
                                start_new_session=os.name != "nt")


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False)
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            process.kill()
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def wait_ready(url: str, process: subprocess.Popen) -> None:
    end = time.monotonic() + 120
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError("Proof process exited during startup")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(.2)
    raise RuntimeError("Proof process readiness timed out")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument("--output-root", type=Path, default=ROOT / "build/worker-dynamic-matching-proof")
    options = parser.parse_args()
    output = options.output_root.resolve()
    if output == ROOT / "build" or not output.is_relative_to(ROOT / "build") or output.exists():
        raise ValueError("Use a fresh output directory beneath repository build")
    # Never attach the proof to a pre-existing local Runtime or Host.
    for port in (18082, 18083, 18086):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", port))
    private = output / "private"
    private.mkdir(parents=True)
    evidence_path = output / "evidence/worker-dynamic-matching.json"
    evidence_path.parent.mkdir()
    inventory = private / "data/scenario-workers"
    spec = materialize(inventory)
    (private / "spec.json").write_text(json.dumps({"workers": spec}), encoding="utf-8")
    assembly = {group: {"eventCodes": [EVENT]} for group in (STRING_GROUP, PHONE_GROUP)}
    simulator_config = private / "worker-simulator.json"
    simulator_config.write_text(json.dumps({
        "runtimeApiBaseUrl": "http://127.0.0.1:18082", "sandboxRoot": str(inventory.resolve()), "controlPort": 18086,
        "workerGroups": {group: {"events": [EVENT], "count": 500, "propertiesTemplate": {}, "newEnvironment": False}
                         for group in (STRING_GROUP, PHONE_GROUP)},
    }), encoding="utf-8")
    config = private / "server.properties"
    config.write_text("xa.mass.worker-assembly.group-config-json=" + json.dumps(assembly) + "\n" + "".join(
        f"xa.mass.worker-matching.rules.worker-groups[{group}][0]=proof.worker.facts\n"
        for group in (STRING_GROUP, PHONE_GROUP)), encoding="utf-8")
    scope = "test_worker_dynamic_matching_" + uuid.uuid4().hex[:12]
    environment = {**os.environ, "XA_MASS_REDIS_URL": options.redis_url, "XA_MASS_REDIS_SCOPE": scope}
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    processes = []
    evidence = {"schemaVersion": 1, "phase": "build", "status": "failed"}
    try:
        subprocess.run([gradle, "--no-daemon", ":distribution:server:bootJar", ":worker_simulator_jvm:installDist",
                        f"{MODULE}:installDist"], cwd=ROOT, env=environment, check=True)
        jars = [p for p in (ROOT / "distribution/server/build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        server = start(["java", "-jar", str(jar), "--spring.profiles.active=scenario-workers",
                        f"--spring.config.additional-location={config.as_uri()}",
                        "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.buffered=false",
                        "--server.tomcat.accesslog.rotate=false", f"--server.tomcat.accesslog.directory={private}",
                        "--server.tomcat.accesslog.prefix=runtime-http", "--server.tomcat.accesslog.suffix=.log",
                        "--server.tomcat.accesslog.pattern=%m %U %s"], output / "runtime-server.log", environment)
        processes.append(server)
        wait_ready("http://127.0.0.1:18082/actuator/health/readiness", server)
        host = start(["java", "-cp", str(ROOT / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*"),
                      "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", str(simulator_config)],
                     output / "scenario-host.log", environment)
        processes.append(host)
        wait_ready("http://127.0.0.1:18086/lab/v1/workers", host)
        harness = start(["java", "-cp", str(ROOT / "integrations/worker-dynamic-matching/build/install/xa-mass-worker-dynamic-matching/lib/*"),
                         "com.xa.mass.integration.workerdynamicmatching.DynamicMatchingMain",
                         "--server=http://127.0.0.1:18082", "--lab=http://127.0.0.1:18086",
                         f"--spec={private / 'spec.json'}", f"--output={evidence_path}",
                         f"--ready={private / 'ready'}", f"--continue={private / 'continue'}"], private / "harness.log", environment)
        processes.append(harness)
        end = time.monotonic() + 150
        while not (private / "ready").exists():
            if any(p.poll() is not None for p in processes) or time.monotonic() >= end:
                raise RuntimeError("Dynamic matching bootstrap failed")
            time.sleep(.1)
        before = read_access(private / "runtime-http.log")
        if prepare_counts(before)["successful"] == 0:
            raise RuntimeError("Initial Prepare was not recorded")
        controls = control_records(inventory)
        pids = {"host": host.pid, "server": server.pid}
        (private / "continue").write_text("continue\n", encoding="utf-8")
        if harness.wait(timeout=780) != 0:
            raise RuntimeError("Dynamic matching Harness failed")
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        if evidence.get("harnessStatus") != "succeeded":
            raise RuntimeError("Harness success missing")
        evidence["prepareAudit"] = audit_prepare(before, read_access(private / "runtime-http.log"))
        evidence["processIds"] = pids
        evidence["processesUnchanged"] = host.poll() is None and server.poll() is None
        evidence["controlFileRecordsUnchanged"] = controls == control_records(inventory)
        if not evidence["processesUnchanged"] or not evidence["controlFileRecordsUnchanged"]:
            raise RuntimeError("Independent process or file audit failed")
        evidence["status"] = "succeeded"
        evidence["phase"] = "complete"
        print("WORKER_DYNAMIC_MATCHING_OK workers=1000 items=150400 prepareDelta=0")
        return 0
    except BaseException as error:
        if evidence_path.exists():
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        evidence["status"] = "failed"
        evidence["runnerFailure"] = type(error).__name__
        raise
    finally:
        evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        for process in reversed(processes):
            stop(process)
        subprocess.run([sys.executable, str(ROOT / ".github/scripts/cleanup_redis_test_scope.py"),
                        "--redis-url", options.redis_url, "--scope", scope], cwd=ROOT, env=environment, check=False)


if __name__ == "__main__":
    raise SystemExit(main())
