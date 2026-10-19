#!/usr/bin/env python3
"""ADB/process choreography; the existing Java lane owns every proof verdict."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen
import uuid

from cleanup_redis_test_scope import cleanup as cleanup_scope

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("xa_android_consumer", ROOT / "xa-android/run_consumer.py")
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)
APPLICATIONS = ["com.xa.mass.integration.androidworker"] + [
    f"com.xa.mass.integration.androidworker.lab{i}" for i in range(1, 4)
]
APK_NAMES = ["xa-mass-android-worker-demo-debug.apk"] + [
    f"xa-mass-android-worker-demo-lab{i}.apk" for i in range(1, 4)
]
FORWARD_PORTS = [18084, 18184, 18185, 18186]
REVERSE_PORTS = [18082, 18083]
MAIN_PREFIX = "com.xa.mass.integration.androidworkerproof.AndroidWorker"


def stop_process(process: subprocess.Popen, timeout: float = 15) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        # Only the process tree started by this harness is targeted.
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       capture_output=True, check=False)
        process.wait(timeout=timeout)
    else:
        import signal
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def spawn(command: list[str], **kwargs) -> subprocess.Popen:
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(command, **kwargs)


def await_marker(process: subprocess.Popen, output: Path, marker: str,
                 timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if output.exists() and marker in output.read_text(
                encoding="utf-8", errors="replace").splitlines():
            return
        if process.poll() is not None:
            raise RuntimeError(f"Proof exited before checkpoint {marker}; see {output}")
        time.sleep(0.1)
    raise TimeoutError(f"Proof checkpoint {marker} timed out; see {output}")


class ProofRunner:
    def __init__(self, options: argparse.Namespace):
        self.options = options
        self.proof_root = options.proof_root.resolve()
        self.evidence = self.proof_root / "evidence"
        self.logs = self.proof_root / "logs"
        self.server = None
        self.proof = None
        self.streams = []
        self.mappings: list[tuple[str, int]] = []
        self.touched_apps: set[str] = set()
        self.scopes: set[str] = set()
        self.freezer_original: tuple[str, str] | None = None
        self.consumption = None
        self.apk_provenance = None

    def adb(self, *args: str, timeout: float = 30) -> str:
        result = subprocess.run(["adb", "-s", self.options.android_serial, *args],
                                capture_output=True, text=True, check=False,
                                timeout=timeout, encoding="utf-8", errors="replace")
        if result.returncode:
            detail = (result.stderr.strip() or result.stdout.strip())[-2000:]
            raise RuntimeError(f"Selected Emulator ADB {args[0]} failed: {detail}")
        return result.stdout.strip()

    def wait_boot(self) -> None:
        self.adb("wait-for-device", timeout=180)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if self.adb("shell", "getprop", "sys.boot_completed") == "1":
                return
            time.sleep(1)
        raise TimeoutError("Selected Emulator did not finish booting")

    def reboot(self) -> None:
        self.adb("reboot")
        # The old transport can still report boot_completed=1 immediately after
        # reboot returns. Fence that transport before accepting the next boot.
        self.adb("wait-for-disconnect", timeout=60)
        self.wait_boot()

    def freezer_enabled(self) -> bool:
        settings = self.adb("shell", "dumpsys", "activity", "settings")
        value = re.search(r"^\s*use_freezer=(true|false)\s*$", settings, re.MULTILINE)
        if value is None:
            raise RuntimeError("Cannot establish Emulator cached-app freezer state")
        return value.group(1) == "true"

    def disable_freezer(self) -> None:
        if not self.freezer_enabled():
            return
        self.freezer_original = (
            self.adb("shell", "device_config", "get", "activity_manager_native_boot", "use_freezer"),
            self.adb("shell", "settings", "get", "global", "cached_apps_freezer"),
        )
        self.adb("shell", "device_config", "put", "activity_manager_native_boot", "use_freezer", "false")
        self.adb("shell", "settings", "put", "global", "cached_apps_freezer", "disabled")
        self.reboot()
        if self.freezer_enabled():
            raise RuntimeError("Emulator cached-app freezer remained enabled")

    def restore_freezer(self) -> None:
        if self.freezer_original is None:
            return
        native, global_value = self.freezer_original
        for prefix, key, value in [
            (["shell", "device_config", "activity_manager_native_boot"], "use_freezer", native),
            (["shell", "settings", "global"], "cached_apps_freezer", global_value),
        ]:
            verb = "delete" if value in {"null", ""} else "put"
            command = [*prefix[:2], verb, *prefix[2:], key]
            if verb == "put":
                command.append(value)
            self.adb(*command)
        self.reboot()
        self.freezer_original = None

    def preflight(self) -> None:
        import redis  # Host dependency; never taken from a Runtime venv.
        self.validate_inputs()
        for command in ("adb", "java"):
            if shutil.which(command) is None:
                raise RuntimeError(f"Required command is unavailable: {command}")
        if (self.adb("get-state") != "device"
                or self.adb("shell", "getprop", "ro.build.version.sdk") != "33"
                or self.adb("shell", "getprop", "ro.kernel.qemu") != "1"):
            raise RuntimeError("Selected device must be an online API 33 Emulator")
        if not list(self.options.proof_runtime.glob("*.jar")):
            raise RuntimeError("Run :integrations:android-worker-proof:stageProofRuntime first")
        if self.evidence.exists() and list(self.evidence.glob("*.json")):
            raise RuntimeError("Proof root already contains evidence; use a new root")
        with redis.Redis.from_url(self.options.redis_url) as client:
            client.ping()
        for port in FORWARD_PORTS + REVERSE_PORTS:
            try:
                with socket.socket() as candidate:
                    candidate.bind(("127.0.0.1", port))
            except OSError as error:
                raise RuntimeError(f"Required proof host port {port} is unavailable") from error
        for direction, ports in [("forward", FORWARD_PORTS), ("reverse", REVERSE_PORTS)]:
            mappings = self.adb(direction, "--list")
            for port in ports:
                if f"tcp:{port}" in mappings.split():
                    raise RuntimeError(f"ADB mapping already exists: {direction} tcp:{port}")
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.logs.mkdir(parents=True, exist_ok=True)
        self.write_consumption()

    def validate_inputs(self) -> None:
        self.consumption = consumer.prepare_options(self.options, self.proof_root / "installation")
        provenance = json.loads((self.options.apk_root / "apk-provenance.json").read_text(encoding="utf-8"))
        for key in ("version", "gitCommit", "archives", "serverJarSha256", "sdkArtifacts"):
            if provenance.get(key) != self.consumption[key]:
                raise RuntimeError(f"APK provenance differs from the pinned ZIP pair: {key}")
        required = {"runtimeApiBaseUrl": "http://127.0.0.1:18082", "projectId": "default",
                    "caller": {"userId": "1000", "role": "admin"}, "profile": "consumer",
                    "pacerPreset": "DEFAULT", "endpointManagerId": consumer.ADAPTER_ID,
                    "independentBuild": True}
        if any(provenance.get(key) != value for key, value in required.items()):
            raise RuntimeError("APK provenance does not describe the independent consumer proof")
        if set(provenance.get("apkSha256", {})) != set(APK_NAMES):
            raise RuntimeError("APK provenance does not contain exactly the four variants")
        for name in APK_NAMES:
            apk = self.options.apk_root / name
            if not apk.is_file() or consumer.digest(apk) != provenance["apkSha256"][name]:
                raise RuntimeError(f"APK bytes differ from provenance: {name}")
        resolved = provenance.get("sdkConsumption", {})
        if (resolved.get("gitCommit") != self.options.commit or resolved.get("version") != self.options.version
                or resolved.get("sdkArchiveSha256") != self.options.sdk_sha256
                or resolved.get("runtimeApiBaseUrl") != required["runtimeApiBaseUrl"]
                or set(resolved.get("variants", {})) != {"debug", "lab1", "lab2", "lab3"}):
            raise RuntimeError("Actual SDK consumption evidence differs")
        expected = {name: {"version": self.options.version, "sha256": info["sha256"]}
                    for name, info in self.consumption["sdkArtifacts"].items()}
        if any(value != expected for value in resolved["variants"].values()):
            raise RuntimeError("Actual resolved SDK bytes differ from the verified SDK ZIP")
        self.apk_provenance = provenance

    def write_consumption(self) -> None:
        if self.consumption is not None and self.evidence.exists():
            encoded = dict(self.consumption, apkProvenance=self.apk_provenance,
                           scopes=sorted(self.scopes), phaseEvidence="*.json",
                           windowPoolInputSha256=consumer.digest(ROOT / "xa-android/config/window-pool-runtime.yaml"))
            (self.evidence / "consumption.json").write_text(json.dumps(encoded, indent=2) + "\n", encoding="utf-8")

    def setup_device(self) -> None:
        print("Preparing selected Emulator and four verified APKs", flush=True)
        self.disable_freezer()
        for package, name in zip(APPLICATIONS, APK_NAMES):
            self.touched_apps.add(package)
            self.adb("install", "-r", str((self.options.apk_root / name).resolve()), timeout=150)
        for direction, ports in [("forward", FORWARD_PORTS), ("reverse", REVERSE_PORTS)]:
            for port in ports:
                self.adb(direction, f"tcp:{port}", f"tcp:{port}")
                self.mappings.append((direction, port))

    def start_app(self, package: str) -> None:
        self.adb("shell", "am", "start", "-W", "-n",
                 f"{package}/com.xa.mass.android.workerdemo.MainActivity")

    def stop_app(self, package: str) -> None:
        self.adb("shell", "am", "force-stop", package)

    def reset_apps(self, packages: list[str]) -> None:
        for package in packages:
            self.stop_app(package)
            self.adb("shell", "pm", "clear", package)
            self.start_app(package)

    def start_server(self, label: str, scope: str, *, configuration: str | None = None) -> None:
        print(f"Starting verified Runtime: {label}", flush=True)
        if self.server is not None:
            raise RuntimeError("Server process is already owned")
        self.scopes.add(scope)
        stream = (self.logs / f"server-{label}.log").open("w", encoding="utf-8")
        self.streams.append(stream)
        environment = dict(os.environ, XA_MASS_CONSUMER_REDIS_URL=self.options.redis_url)
        runtime_config = ROOT / "xa-android/config" / configuration if configuration else None
        groups = ROOT / "xa-android/config/worker-groups.json"
        example = Path(self.consumption["runtimeRoot"]) / "examples/batch-checks"
        if configuration in {"batch-checks-runtime.yaml", "batch-checks-window.yaml"}:
            runtime_config = example / "batch-checks-runtime.yaml"
            groups = example / "worker-groups.json"
            seed = example / "batch-checks-configuration.json"
            state_name = "app-checks"
            if configuration == "batch-checks-window.yaml":
                inputs = self.proof_root / "window-inputs"
                inputs.mkdir(exist_ok=True)
                seed_document = json.loads(seed.read_text(encoding="utf-8"))
                execution = seed_document["checks"]["android-md5-prefix"]["execution"]
                execution["refill"] = [{"poolName": "device-window", "target": {}, "count": 100}]
                execution["workerSelector"] = {"executorName": "worker.device.available", "input": {}}
                seed = inputs / "checks.json"
                seed.write_text(json.dumps(seed_document, ensure_ascii=False), encoding="utf-8")
                runtime_config = inputs / "runtime.yaml"
                runtime_config.write_text((ROOT / "xa-android/config/window-pool-runtime.yaml").read_text(encoding="utf-8")
                        + '\n"xa.mass.scenarios.app-checks.enabled": true\n'
                        + '"xa.mass.scenarios.app-checks.configuration.path": ${XA_MASS_APP_CHECKS_CONFIG_PATH}\n'
                        + '"xa.mass.scenarios.app-checks.configuration.seed": ${XA_MASS_APP_CHECKS_SEED}\n', encoding="utf-8")
                state_name = "app-checks-window"
            environment.update(
                XA_MASS_APP_CHECKS_CONFIG_PATH=str(self.proof_root / "data" / state_name / "configuration.json"),
                XA_MASS_APP_CHECKS_SEED=seed.as_uri())
        self.consumption.setdefault("runtimeInputs", {})[label] = {
            "groupsSha256": consumer.digest(groups),
            "runtimeSha256": consumer.digest(runtime_config) if runtime_config else None,
            "seedSha256": consumer.digest(seed) if configuration in {"batch-checks-runtime.yaml", "batch-checks-window.yaml"} else None,
        }
        self.write_consumption()
        command = consumer.server_command(self.consumption, scope, 18082, 18083, groups=groups, runtime_config=runtime_config)
        self.server = spawn(command,
                            cwd=self.proof_root, env=environment,
                            stdout=stream, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                raise RuntimeError(f"Server exited before readiness; see {stream.name}")
            try:
                with urlopen("http://127.0.0.1:18082/actuator/health/readiness", timeout=2) as response:
                    if response.status == 200:
                        return
            except (URLError, OSError):
                pass
            time.sleep(1)
        raise TimeoutError(f"Server readiness timed out; see {stream.name}")

    def stop_server(self) -> None:
        if self.server is not None:
            stop_process(self.server, 20)
            self.server = None

    def phase(self, main: str, proof_id: str, phase: str, evidence: str,
              baseline: str | None = None, marker: str | None = None,
              mutation=None) -> None:
        print(f"Running Java proof: {main}/{phase}", flush=True)
        output = self.proof_root / f"{evidence}.out"
        arguments = [
            "--server-base-url=http://127.0.0.1:18082",
            f"--proof-id={proof_id}", f"--phase={phase}",
            "--endpoint-manager-id=runtime-websocket",
            f"--evidence-file={self.evidence / (evidence + '.json')}",
            f"--maximum-wait-millis={self.options.maximum_wait_millis}",
            "--request-timeout-millis=5000", "--android-api-level=33",
        ]
        arguments.append("--device-base-url=http://127.0.0.1:18084")
        if baseline:
            arguments.append(f"--baseline-file={self.evidence / (baseline + '.json')}")
        stream = output.open("w", encoding="utf-8")
        self.streams.append(stream)
        self.proof = spawn(["java", "-cp", str(self.options.proof_runtime.resolve() / "*"),
                            MAIN_PREFIX + main + "Main", *arguments],
                           cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        budget = self.options.maximum_wait_millis / 1000 + 10
        deadline = time.monotonic() + budget
        if marker:
            await_marker(self.proof, output, marker, budget)
            mutation()
        result = self.proof.wait(timeout=max(0.01, deadline - time.monotonic()))
        self.proof = None
        if result != 0:
            raise RuntimeError(f"Java proof {main}/{phase} failed; see {output}")

    def run(self) -> None:
        self.preflight()
        self.setup_device()
        scope = self.options.redis_scope
        self.start_server("correctness", scope + "_correctness")
        self.reset_apps(APPLICATIONS[:1])
        self.phase("Correctness", "android-worker-correctness", "initial", "correctness-initial")
        self.stop_app(APPLICATIONS[0])
        self.phase("Correctness", "android-worker-correctness", "process-restart",
                   "correctness-process-restart", "correctness-initial",
                   "ANDROID_WORKER_PROCESS_STOP_OBSERVED", lambda: self.start_app(APPLICATIONS[0]))
        self.stop_app(APPLICATIONS[0])
        self.stop_server()

        self.start_server("convergence", scope + "_convergence")
        self.reset_apps(APPLICATIONS[:1])
        self.phase("ConvergenceHealth", "android-worker-convergence-health", "active", "convergence-active")
        self.phase("ConvergenceHealth", "android-worker-convergence-health", "process-loss",
                   "convergence-process-loss", "convergence-active",
                   "ANDROID_WORKER_IN_FLIGHT_PROCESS_LOSS_READY", lambda: self.stop_app(APPLICATIONS[0]))
        self.start_app(APPLICATIONS[0])
        self.phase("ConvergenceHealth", "android-worker-convergence-health", "process-loss-recovery",
                   "convergence-process-loss-recovery", "convergence-process-loss")
        self.stop_server()
        self.phase("ConvergenceHealth", "android-worker-convergence-health", "terminal",
                   "convergence-terminal", "convergence-active")
        self.start_server("convergence-restart", scope + "_convergence")
        self.phase("ConvergenceHealth", "android-worker-convergence-health", "server-restart",
                   "convergence-server-restart", "convergence-active")
        self.stop_app(APPLICATIONS[0])
        self.stop_server()

        self.start_server("triad-correctness", scope + "_triad_correctness")
        self.reset_apps(APPLICATIONS[1:])
        self.phase("TriadCorrectness", "android-worker-triad-correctness", "initial", "triad-correctness")
        for package in APPLICATIONS[1:]:
            self.stop_app(package)
        self.stop_server()
        self.start_server("triad-convergence", scope + "_triad_convergence")
        self.reset_apps(APPLICATIONS[1:])
        for phase, baseline in [("baseline", None), ("outage", "triad-convergence-baseline"),
                                ("recovery", "triad-convergence-baseline")]:
            if phase == "outage":
                self.stop_app(APPLICATIONS[2])
            elif phase == "recovery":
                self.start_app(APPLICATIONS[2])
            self.phase("TriadConvergenceHealth", "android-worker-triad-convergence-health", phase,
                       f"triad-convergence-{phase}", baseline)

        for package in APPLICATIONS[1:]:
            self.stop_app(package)
        self.stop_server()
        self.start_server("window-pool", scope + "_window_pool", configuration="window-pool-runtime.yaml")
        self.reset_apps(APPLICATIONS[:1])
        self.phase("WindowPool", "android-window-pool", "initial", "window-pool")
        self.stop_app(APPLICATIONS[0])
        self.stop_server()
        self.start_server("batch-checks", scope + "_batch_checks", configuration="batch-checks-runtime.yaml")
        self.reset_apps(APPLICATIONS[:1])
        self.phase("BatchChecks", "android-batch-checks", "initial", "batch-checks-initial")
        self.stop_app(APPLICATIONS[0])
        self.stop_server()
        self.start_server("batch-checks-restart", scope + "_batch_checks", configuration="batch-checks-runtime.yaml")
        self.phase("BatchChecks", "android-batch-checks", "restart", "batch-checks-restart", "batch-checks-initial")
        self.stop_server()
        self.start_server("batch-checks-window", scope + "_batch_checks_window", configuration="batch-checks-window.yaml")
        self.reset_apps(APPLICATIONS[:1])
        self.phase("BatchChecks", "android-batch-checks-window", "window", "batch-checks-window")

    def cleanup(self) -> None:
        errors = []
        actions = []
        if self.proof is not None:
            actions.append(lambda: stop_process(self.proof))
        actions.append(self.stop_server)
        for package in sorted(self.touched_apps):
            actions.append(lambda package=package: self.stop_app(package))
        for direction, port in reversed(self.mappings):
            actions.append(lambda direction=direction, port=port: self.adb(direction, "--remove", f"tcp:{port}"))
        for scope in sorted(self.scopes):
            actions.append(lambda scope=scope: cleanup_scope(self.options.redis_url, scope))
        if self.logs.exists():
            def collect_log():
                log = self.adb("logcat", "-d", "-v", "threadtime")
                filtered = [line for line in log.splitlines() if re.search(
                    r"AndroidRuntime|com\.xa\.mass|AndroidWorker|WorkerRunController|AndroidOkHttp", line)]
                (self.logs / "android-filtered.log").write_text("\n".join(filtered) + "\n", encoding="utf-8")
            actions.append(collect_log)
        actions.append(self.restore_freezer)
        for action in actions:
            try:
                action()
            except Exception as error:
                errors.append(type(error).__name__)
        for stream in self.streams:
            stream.close()
        if errors:
            raise RuntimeError("Proof cleanup did not finish: " + ", ".join(errors))


def parse_options(arguments=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-serial", default=os.environ.get("ANDROID_SERIAL"), required=not os.environ.get("ANDROID_SERIAL"))
    consumer.add_archive_arguments(parser)
    parser.add_argument("--apk-root", type=Path, required=True)
    parser.add_argument("--proof-runtime", type=Path, default=ROOT / "integrations/android-worker-proof/build/proof-runtime")
    parser.add_argument("--proof-root", type=Path, default=Path(os.environ.get("ANDROID_WORKER_PROOF_ROOT", ROOT / "build" / f"android-emulator-proof-{uuid.uuid4().hex}")))
    parser.add_argument("--redis-url", default=os.environ.get("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15"))
    parser.add_argument("--redis-scope", default=os.environ.get("XA_MASS_REDIS_SCOPE", f"test_android_emulator_{uuid.uuid4().hex}"))
    parser.add_argument("--maximum-wait-millis", type=int, default=180000)
    options = parser.parse_args(arguments)
    if not options.android_serial.strip() or not re.fullmatch(r"test_[a-z0-9_]+", options.redis_scope):
        parser.error("A non-empty device serial and an exact test_* scope are required")
    if not 1 <= options.maximum_wait_millis <= 300000:
        parser.error("maximum wait must be in 1..300000 milliseconds")
    return options


def main(arguments=None) -> int:
    runner = ProofRunner(parse_options(arguments))
    result = 0
    try:
        runner.run()
        print(f"Android Worker Proof passed; evidence={runner.evidence}")
    except Exception as error:
        print(f"Android Worker Proof failed: {error}", file=sys.stderr)
        result = 1
    finally:
        try:
            runner.cleanup()
        except Exception as error:
            print(str(error), file=sys.stderr)
            result = 1
    return result


if __name__ == "__main__":
    raise SystemExit(main())
