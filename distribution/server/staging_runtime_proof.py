"""Packaged Runtime + real Java Host through the staging TLS gateway.

This is an existing Runtime Distribution witness, not a deployment or load test.
All services are local, Redis writes use the caller's exact test_* scope, and only
owned child processes are stopped. The caller owns exact-scope Redis cleanup.
"""

from __future__ import annotations

from contextlib import ExitStack
from dataclasses import asdict
import hashlib
from html.parser import HTMLParser
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import secrets
import subprocess
import sys
import time
from urllib.parse import urlsplit

from distribution.acceptance.finite_round import EVENT
from distribution.server.local_runtime import child_environment
from distribution.server.staging_ingress.proof import (
    OTHER_WORKER, WORKER, Gateway, basic, free_port,
)
from integrations.worker_proof_support.processes import start_process, stop_process


# Administrator 1000 receives a fresh private key in this proof's users file; the public
# development key of the same user must be rejected through the gateway.
ADMIN_KEY = "xak_" + secrets.token_urlsafe(32)
# Kit rounds run as a separate operator granted only the default Project, never as the administrator.
EXTERNAL_USER = "external-user"
EXTERNAL_KEY = "xak_" + secrets.token_urlsafe(32)
PUBLIC_DEVELOPMENT_KEY = "xak_dev_1000"
GROUP_A = "ingress-java-a"
GROUP_B = "ingress-java-b"


class ProofFailure(RuntimeError):
    """Only caller-authored, payload-free assertion text is safe for CI output."""


def _require(condition, message: str) -> None:
    if not condition:
        raise ProofFailure(message)


def _json(gate: Gateway, method: str, path: str, body=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    status, payload, _ = gate.request("localhost", method, path, ADMIN_KEY, headers=headers,
                                      body=None if body is None else json.dumps(body).encode("utf-8"))
    _require(status == 200, f"Packaged gateway request failed: {method} {path}, HTTP {status}")
    return json.loads(payload)


class _ConsoleEntrypoints(HTMLParser):
    def __init__(self):
        super().__init__()
        self.assets = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "script" and attributes.get("src"):
            self.assets.append(("script", attributes["src"]))
        if tag == "link" and attributes.get("rel") == "stylesheet" and attributes.get("href"):
            self.assets.append(("style", attributes["href"]))


def _console(gate: Gateway, runtime_root: Path) -> dict:
    status, index, headers = gate.request("localhost", "GET", "/")
    content_type = {name.lower(): value for name, value in headers.items()}.get("content-type", "")
    _require(status == 200 and content_type.startswith("text/html"), "Protected Console index was not served as HTML")
    frontend = (runtime_root / "frontend/dist").resolve()
    _require(index == (frontend / "index.html").read_bytes(), "Console index differs from packaged frontend")
    parser = _ConsoleEntrypoints()
    parser.feed(index.decode("utf-8"))
    _require({kind for kind, _ in parser.assets} == {"script", "style"}, "Console index must reference its packaged JS and CSS")
    hashes = {}
    for kind, reference in parser.assets:
        parsed = urlsplit(reference)
        _require(not parsed.scheme and not parsed.netloc and parsed.path.startswith("/"), "Console asset must be same-origin")
        source = (frontend / parsed.path.lstrip("/")).resolve()
        _require(source.is_relative_to(frontend) and source.is_file(), "Console asset must belong to the packaged frontend")
        status, content, headers = gate.request("localhost", "GET", reference)
        content_type = {name.lower(): value for name, value in headers.items()}.get("content-type", "").split(";", 1)[0]
        expected_types = ("application/javascript", "text/javascript") if kind == "script" else ("text/css",)
        _require(status == 200 and content_type in expected_types and content == source.read_bytes(),
                 "Console asset was denied, changed, or replaced with SPA HTML")
        hashes[parsed.path] = hashlib.sha256(content).hexdigest()
    return {"entrypointAssets": hashes}


def _wait(predicate, processes, seconds: float, label: str):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        _require(all(process.poll() is None for process in processes), f"Owned process exited during {label}; inspect private proof logs")
        result = predicate()
        if result:
            return result
        time.sleep(0.25)
    raise ProofFailure(f"Packaged ingress proof timed out: {label}")


def _ready(runtime_port: int) -> bool:
    connection = http.client.HTTPConnection("127.0.0.1", runtime_port, timeout=2)
    try:
        connection.request("GET", "/actuator/health/readiness")
        response = connection.getresponse()
        return response.status == 200 and json.loads(response.read()).get("status") == "UP"
    except (OSError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def _users_file(directory: Path) -> Path:
    """Built-in roles and users with private keys: administrator 1000 and the external operator."""
    source = Path(__file__).resolve().parents[2] / "server_jvm/src/main/resources/users/development-directory.json"
    document = json.loads(source.read_text(encoding="utf-8"))
    for user in document["users"]:
        user.pop("accessKeySha256", None)
        if user["userId"] == "1000":
            user["accessKeySha256"] = hashlib.sha256(ADMIN_KEY.encode("utf-8")).hexdigest()
    document["users"].append({"userId": EXTERNAL_USER, "displayName": "External user", "role": "operator",
                              "projectIds": ["default"],
                              "accessKeySha256": hashlib.sha256(EXTERNAL_KEY.encode("utf-8")).hexdigest()})
    path = directory / "users.json"
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def _runtime_command(runtime_root: Path, data_root: Path, manifest: dict, java: str,
                     runtime_port: int, adapter_port: int, websocket_url: str,
                     redis_url: str, scope: str) -> list[str]:
    jar = (runtime_root / manifest["serverJar"]).resolve()
    _require(jar.is_relative_to(runtime_root) and jar.is_file(), "Runtime manifest JAR must belong to the extracted archive")
    groups = {group: {"attributes": {"capability": "ingress-md5-proof"}, "eventCodes": [EVENT]}
              for group in (GROUP_A, GROUP_B)}
    return [java, "-Xms128m", "-Xmx1g", "-jar", str(jar),
            "--spring.profiles.active=consumer", "--server.address=127.0.0.1", f"--server.port={runtime_port}",
            "--spring.web.resources.static-locations=" + (runtime_root / "frontend/dist").as_uri() + "/",
            f"--xa.mass.redis.url={redis_url}", f"--xa.mass.redis.scope={scope}",
            "--xa.mass.users.seed=" + _users_file(data_root).resolve().as_uri(),
            "--xa.mass.project-assembly.projects[0].project-id=default",
            "--xa.mass.project-assembly.projects[0].worker-group-ids=",
            "--xa.mass.worker-assembly.group-config-json=" + json.dumps(groups, separators=(",", ":")),
            f"--xa.mass.worker-delivery.adapter.remote-base-url=http://127.0.0.1:{runtime_port}",
            f"--xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port={adapter_port}",
            "--xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-host=127.0.0.1",
            f"--xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri={websocket_url}",
            f"--xa.mass.task-retention.directory={data_root / 'task-archives'}",
            *(argument for group in groups for argument in (
                f"--xa.mass.worker-matching.groups.{group}.pools[0]=any",
                f"--xa.mass.worker-matching.groups.{group}.functions[0]=worker.any"))]


def _truststore(java: str, gateway: Gateway, directory: Path) -> Path:
    keytool = Path(java).resolve().with_name("keytool.exe" if os.name == "nt" else "keytool")
    _require(keytool.is_file(), "Runtime Distribution TLS proof requires keytool beside Java")
    path = directory / "worker-trust.p12"
    completed = subprocess.run([str(keytool), "-importcert", "-noprompt", "-alias", "ingress-proof",
                                "-file", str(gateway.ca_file), "-keystore", str(path), "-storetype", "PKCS12",
                                "-storepass", "fixture-truststore-password"], capture_output=True, timeout=30)
    _require(completed.returncode == 0, "Could not import proof CA into Java truststore")
    return path


def _render_packaged_gateway(gate: Gateway, runtime_root: Path, environment: dict) -> None:
    package = runtime_root / "examples/staging-ingress"
    _require((package / "staging_ingress/__main__.py").is_file(), "Runtime ZIP does not contain the ingress renderer")
    configuration = gate.directory / "deployment.json"
    configuration.write_text(json.dumps(asdict(gate.config)), encoding="utf-8")
    clean = dict(environment)
    clean.pop("PYTHONPATH", None)
    completed = subprocess.run([sys.executable, "-m", "staging_ingress", "render", "--config", str(configuration),
                                "--output", str(gate.configuration)], cwd=package, env=clean,
                               capture_output=True, timeout=20)
    _require(completed.returncode == 0, "Packaged ingress renderer failed without checkout fallback")
    rendered = gate.configuration.read_text(encoding="utf-8")
    _require(f"server_name {gate.config.admin_host}" in rendered
             and f"server_name {gate.config.worker_host}" in rendered,
             "Packaged ingress renderer did not use fixture public origins")


def _start_host(stack: ExitStack, *, group: str, count: int, credential: tuple[str, str],
                gate: Gateway, java: str, simulator_home: Path, truststore: Path,
                directory: Path, environment: dict):
    directory.mkdir(parents=True)
    secret_file = directory / "credential.json"
    secret_file.write_text(json.dumps({"keyId": credential[0], "secret": credential[1]}), encoding="utf-8")
    secret_file.chmod(0o600)
    configuration = directory / "simulator.json"
    configuration.write_text(json.dumps({
        "runtimeApiBaseUrl": gate.worker_url, "gatewayCredentialFile": "credential.json", "controlPort": 0,
        "sandboxRoot": "data/scenario-workers", "startupPlan": {"workerCounts": {group: count}},
        "workerGroups": {group: {"count": count, "events": [EVENT], "propertiesTemplate": {}}},
    }), encoding="utf-8")
    command = [java, "-Xms64m", "-Xmx256m", f"-Djavax.net.ssl.trustStore={truststore}",
               "-Djavax.net.ssl.trustStorePassword=fixture-truststore-password",
               "-cp", str(simulator_home / "lib" / "*"),
               "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", str(configuration)]
    process = start_process(command, directory / "host.log", environment, cwd=directory)
    stack.callback(stop_process, process, force=False)
    return process


def _workers(gate: Gateway, group: str, expected: int):
    page = _json(gate, "POST", f"/api/v1/runtime-view/worker-groups/{group}/workers:preview", 100)
    workers = page.get("workers", [])
    if len(workers) != expected or any(not isinstance(worker.get("workerProperties"), dict) for worker in workers):
        return None
    identities = {worker["workerId"] for worker in workers}
    if len(identities) != expected or any(worker["workerGroupId"] != group
                                         or worker.get("endpointManagerId") != "runtime-websocket" for worker in workers):
        return None
    coordinates = {worker["workerProperties"].get("labInventoryLine") for worker in workers}
    return identities if coordinates == {str(index + 1) for index in range(expected)} else None


def _network(gate: Gateway, ids: set[str]) -> dict:
    return _json(gate, "POST", "/api/v1/runtime-view/endpoint-managers/runtime-websocket/workers:network-observe",
                 sorted(ids))["statesByWorkerId"]


def _finite(gate: Gateway, group: str, label: str, processes, *, simulator_home: Path, proof_root: Path,
            environment: dict) -> dict:
    """One round through the kit's shipped command, as the non-administrator external user."""
    key_file = proof_root / "external-access-key.txt"
    if not key_file.exists():
        key_file.write_text(EXTERNAL_KEY + "\n", encoding="utf-8")
        key_file.chmod(0o600)
    command = [sys.executable, "-I", str(simulator_home / "acceptance/finite_round.py"),
               "--runtime-url", gate.admin_url, "--access-key-file", str(key_file),
               "--group", group, "--timeout-seconds", "90"]
    clean = {**environment, "SSL_CERT_FILE": str(gate.ca_file)}
    clean.pop("PYTHONPATH", None)
    completed = subprocess.run(command, cwd=proof_root, env=clean, capture_output=True, timeout=150)
    (proof_root / f"round-{label}.log").write_bytes(completed.stdout + b"\n" + completed.stderr)
    _require(all(process.poll() is None for process in processes), f"Owned process exited during {label} round")
    lines = completed.stdout.decode("utf-8", errors="replace").splitlines()
    _require(len(lines) == 1, f"Kit round {label} did not print exactly one JSON line")
    record = json.loads(lines[0])
    _require(completed.returncode == 0 and record.get("outcome") == "passed" and record.get("group") == group,
             f"Kit round {label} did not pass: outcome={record.get('outcome')} phase={record.get('phase')}")
    point = _json(gate, "GET", f"/api/v1/projects/default/tasks/{record['taskId']}")
    _require(point.get("scoreBand") == "terminal" and point["task"]["workerGroupId"] == group
             and point["task"].get("metadata", {}).get("userId") == EXTERNAL_USER,
             "Kit round Task is not the external user's terminal Task in the declared Group")
    return {"taskId": record["taskId"], "group": group, "items": record["items"],
            "exportSha256": record["exportSha256"], "kitVersion": record["kitVersion"],
            "approveToTerminalMillis": record["durationsMillis"]["approveToTerminal"]}


def verify(*, runtime_root: Path, simulator_home: Path, redis_url: str, scope: str, proof_root: Path) -> dict:
    """Run on Linux with real nginx/openssl/Java and a caller-cleaned Redis test scope."""
    evidence = {"phase": "preflight"}
    try:
        return _verify(runtime_root=runtime_root, simulator_home=simulator_home, redis_url=redis_url,
                       scope=scope, proof_root=proof_root, evidence=evidence)
    except Exception as error:
        failure = {"status": "failed", "phase": evidence["phase"], "exceptionType": type(error).__name__,
                   "workerCounts": evidence.get("workerCounts", {}),
                   "reason": str(error) if isinstance(error, ProofFailure) else "Inspect the failing proof stage; raw responses and logs are private"}
        if evidence.get("ownedProofRoot"):
            (Path(proof_root) / "failure-summary.json").write_text(json.dumps(failure, indent=2) + "\n", encoding="utf-8")
        # Default CI output remains useful after a caller removes its temporary directory.
        # Never attach a parser/HTTP/process cause which could contain a secret or payload.
        reported = RuntimeError("Packaged ingress witness failed: " + json.dumps(failure))
        reported.safe_evidence = failure
        raise reported from None


def _verify(*, runtime_root: Path, simulator_home: Path, redis_url: str, scope: str,
            proof_root: Path, evidence: dict) -> dict:
    _require(os.name == "posix", "Packaged ingress proof requires Linux Nginx")
    _require(re.fullmatch(r"test_[a-z0-9_]+", scope) is not None, "Ingress proof requires an exact test_* scope")
    runtime_root, simulator_home, proof_root = (Path(path).resolve() for path in (runtime_root, simulator_home, proof_root))
    _require(not proof_root.exists(), "Ingress proof directory must be new")
    proof_root.mkdir(parents=True, mode=0o700)
    evidence["ownedProofRoot"] = True
    manifest = json.loads((runtime_root / "manifest.json").read_text(encoding="utf-8"))
    host_jars = sorted((simulator_home / "lib").glob("*.jar"))
    kit_manifest = simulator_home / "manifest.json"
    _require(bool(host_jars) and kit_manifest.is_file() and (simulator_home / "acceptance/finite_round.py").is_file(),
             "The external Host must be the extracted Worker Simulator kit")
    kit = json.loads(kit_manifest.read_text(encoding="utf-8"))
    _require(kit.get("version") == manifest["version"] and kit.get("gitCommit") == manifest["gitCommit"],
             "Worker Simulator kit and Runtime must come from the same build")
    java = shutil.which("java")
    _require(java is not None, "Ingress proof requires Java on PATH")
    java = str(Path(java).resolve())
    environment = child_environment()
    ports = set()
    while len(ports) != 4:
        ports.add(free_port())
    runtime_port, adapter_port, http_port, https_port = sorted(ports)
    gate = Gateway(proof_root / "gateway", runtime_port, adapter_port, worker_groups=(GROUP_A, GROUP_B),
                   http_port=http_port, https_port=https_port)
    _render_packaged_gateway(gate, runtime_root, environment)
    truststore = _truststore(java, gate, proof_root)
    witnesses = []
    with ExitStack() as stack:
        evidence["phase"] = "runtime-startup"
        server = start_process(_runtime_command(runtime_root, proof_root, manifest, java, runtime_port,
                               adapter_port, gate.websocket_url, redis_url, scope),
                               proof_root / "runtime.log", environment, cwd=runtime_root)
        stack.callback(stop_process, server, force=False)
        _wait(lambda: _ready(runtime_port), [server], 120, "packaged Runtime readiness")
        evidence["phase"] = "gateway-and-console"
        stack.enter_context(gate)
        _require(gate.request("localhost", "GET", "/")[0] == 200, "Console page must load before access-key login")
        _require(gate.request("localhost", "GET", "/api/v1/projects")[0] == 401, "Unauthenticated admin request was accepted")
        _require(gate.request("localhost", "GET", "/api/v1/projects", WORKER)[0] == 401, "Worker key reached management")
        _require(gate.request("localhost", "GET", "/api/v1/projects", PUBLIC_DEVELOPMENT_KEY)[0] == 401,
                 "Public development key was accepted by an external users file")
        _require(_json(gate, "GET", "/api/v1/me")["userId"] == "1000", "Private administrator key did not authenticate")
        console = _console(gate, runtime_root)
        for group in (GROUP_A, GROUP_B):
            _require(_json(gate, "POST", f"/api/v1/runtime-view/worker-groups/{group}/workers:preview", 100)["workers"] == [],
                     "Runtime must not start Workers implicitly")
        evidence["phase"] = "worker-startup"
        a = _start_host(stack, group=GROUP_A, count=2, credential=WORKER, gate=gate, java=java,
                        simulator_home=simulator_home, truststore=truststore, directory=proof_root / "host-a", environment=environment)
        b = _start_host(stack, group=GROUP_B, count=1, credential=OTHER_WORKER, gate=gate, java=java,
                        simulator_home=simulator_home, truststore=truststore, directory=proof_root / "host-b", environment=environment)
        processes = [server, a, b]
        ids_a = _wait(lambda: _workers(gate, GROUP_A, 2), processes, 60, "two batch-prepared A Workers and Facts")
        ids_b = _wait(lambda: _workers(gate, GROUP_B, 1), processes, 60, "B Worker and Facts")
        evidence["workerCounts"] = {GROUP_A: len(ids_a), GROUP_B: len(ids_b)}
        _require(ids_a.isdisjoint(ids_b), "Separate fixture Groups reused Worker identity")
        def connected():
            states = _network(gate, ids_a | ids_b)
            return all(states.get(identity) == "connected" for identity in ids_a | ids_b)
        _wait(connected, processes, 30, "all Worker WebSocket routes")
        evidence["phase"] = "initial-finite-task"
        round_inputs = {"simulator_home": simulator_home, "proof_root": proof_root, "environment": environment}
        witnesses.append(_finite(gate, GROUP_A, "initial", processes, **round_inputs))

        # No artificial application heartbeat or API traffic during the idle window.
        evidence["phase"] = "idle-window"
        deadline = time.monotonic() + 65
        while time.monotonic() < deadline:
            _require(all(process.poll() is None for process in processes), "Owned process exited during idle window")
            time.sleep(0.5)
        evidence["phase"] = "post-idle-finite-task"
        witnesses.append(_finite(gate, GROUP_A, "after-idle", processes, **round_inputs))

        evidence["phase"] = "credential-revocation-and-reconnect"
        gate.write_passwords(gate.worker_password_file, (OTHER_WORKER,))
        gate.restart()
        _require(gate.websocket(WORKER)[0] == 401, "Revoked Worker credential opened a new WebSocket")
        def reconnected():
            states = _network(gate, ids_a | ids_b)
            return all(states.get(identity) != "connected" for identity in ids_a) and all(
                states.get(identity) == "connected" for identity in ids_b)
        _wait(reconnected, processes, 45, "revoked A disconnected and remaining B reconnected")
        evidence["phase"] = "post-revocation-finite-task"
        witnesses.append(_finite(gate, GROUP_B, "after-revocation", processes, **round_inputs))
        access = (Path(gate.config.state_directory) / "access.log").read_text(encoding="utf-8")
        _require(f"POST /api/v1/worker-groups/{GROUP_A}/workers:prepare-batch 200" in access,
                 "Actual Simulator did not traverse batch Prepare through the gateway")

    evidence["phase"] = "safe-evidence"
    for log in proof_root.rglob("*.log"):
        contents = log.read_text(encoding="utf-8", errors="replace")
        _require(ADMIN_KEY not in contents and EXTERNAL_KEY not in contents
                 and all(secret not in contents and basic((key, secret)) not in contents
                         for key, secret in (WORKER, OTHER_WORKER)),
                 "Credential material appeared in a private process log")
    summary = {"status": "passed", "runtimeVersion": manifest["version"], "runtimeCommit": manifest["gitCommit"],
               "console": console,
               "gatewayConfigSha256": hashlib.sha256(gate.configuration.read_bytes()).hexdigest(),
               "gatewayRendererSha256": hashlib.sha256((runtime_root / "examples/staging-ingress/staging_ingress/renderer.py").read_bytes()).hexdigest(),
               "runtimeJarSha256": hashlib.sha256((runtime_root / manifest["serverJar"]).read_bytes()).hexdigest(),
               "simulatorJars": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in host_jars},
               "simulatorKit": {"version": kit["version"], "gitCommit": kit["gitCommit"], "roundSha256":
                                hashlib.sha256((simulator_home / "acceptance/finite_round.py").read_bytes()).hexdigest()},
               "workerCounts": {GROUP_A: len(ids_a), GROUP_B: len(ids_b)},
               "workers": {GROUP_A: sorted(ids_a), GROUP_B: sorted(ids_b)}, "tasks": witnesses,
               "totalCheckedItems": sum(row["items"] for row in witnesses), "idleSeconds": 65,
               "revokedHostDisconnected": True, "remainingHostExecutedAfterReconnect": True,
               "claims": "Packaged Runtime and the published Worker Simulator kit over verified TLS gateway; the kit's "
                         "finite-round command as a Project-scoped operator; Any finite execution and raw export"}
    (proof_root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary
