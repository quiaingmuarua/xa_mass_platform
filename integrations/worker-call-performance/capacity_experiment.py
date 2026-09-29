"""External lifecycle for the Java-owned capacity experiment. No QPS or acceptance calculations."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import sys
import time
import uuid


def verify_bundle(root):
    manifest = root / "experiment-manifest.json"
    if not manifest.is_file():
        return None
    for line in (root / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        path = (root / name).resolve()
        if not path.is_relative_to(root.resolve()) or not path.is_file():
            raise RuntimeError("Bundle file missing or outside root")
        hasher = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                hasher.update(chunk)
        if hasher.hexdigest() != digest:
            raise RuntimeError("Bundle checksum mismatch: " + name)
    return json.loads(manifest.read_text())


def harness_command(runner, phase, output, config, *extra, heap=1024):
    return ["java", f"-Xmx{heap}m", "-XX:+ExitOnOutOfMemoryError", "-cp",
            runner.MODULE / "build/install/xa-mass-worker-call-performance/lib/*",
            "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain",
            f"--phase={phase}", f"--output={output}", f"--experiment-config={config}", *extra]


def run_case(runner, root, config_path, config, profile, stage, path, repetition, cpu_ids):
    import redis
    root.mkdir()
    evidence, private = root / "evidence", root / "private"
    evidence.mkdir()
    private.mkdir(mode=0o700)
    resources = config["resources"]
    deadline = time.monotonic() + config["caseTimeoutSeconds"]
    scope = "test_worker_call_capacity_" + uuid.uuid4().hex[:16]
    processes, errors = {}, []
    container = client = sampler = None
    case = dict(status="failed", evidenceStatus="incomplete", invalidReasons=[])
    args = (f"--experiment-profile={profile['name']}", f"--experiment-stage={stage}")
    cpus = ",".join(map(str, cpu_ids))
    env = {k: v for k, v in os.environ.items() if not k.startswith(("XA_MASS_", "SPRING_"))
           and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}

    def launch(role, command):
        process = runner.start_process(command, private / (role + ".log"), env, cpu_ids, resources["fileDescriptors"])
        processes[role] = process
        if sampler:
            sampler.register(role, process)
        return process

    def phase(name, *extra):
        destination = evidence / name
        process = launch("harness-" + name, harness_command(runner, name, destination, config_path, *args, *extra,
                                                           heap=resources["harnessHeapMiB"]))
        while process.poll() is None:
            if time.monotonic() >= deadline:
                raise RuntimeError("Case deadline exceeded")
            if sampler.failure:
                raise RuntimeError(sampler.failure)
            if any(processes[role].poll() is not None for role in ("server", "host")):
                raise RuntimeError("Experiment process exited")
            time.sleep(.25)
        summary = json.loads((destination / (name + ".json")).read_text())
        if process.returncode or summary.get("status") != "passed":
            raise RuntimeError("Harness phase failed: " + name)
        return summary

    try:
        import socket
        for port in (18082, 18083, 18086):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        container = runner.command(["docker", "run", "--rm", "-d", "--label", f"xa-mass-proof={scope}",
                                    "--cpuset-cpus", cpus, "--memory", str(resources["redisMemoryMiB"]) + "m",
                                    "--memory-swap", str(resources["redisMemoryMiB"]) + "m",
                                    "-p", "127.0.0.1::6379", runner.REDIS_IMAGE, "redis-server",
                                    "--maxmemory", str(resources["redisMaxmemoryMiB"]) + "mb",
                                    "--maxmemory-policy", "noeviction", "--save", "", "--appendonly", "no"])
        if not re.fullmatch(r"[0-9a-f]{64}", container):
            container = None
            raise RuntimeError("Invalid disposable container identity")
        port = int(runner.command(["docker", "port", container, "6379/tcp"]).split(":")[-1])
        redis_url = f"redis://127.0.0.1:{port}/15"
        client = redis.Redis.from_url(redis_url, decode_responses=True, socket_timeout=2)
        for attempt in range(40):
            try:
                client.ping()
                break
            except redis.ConnectionError:
                if attempt == 39:
                    raise
                time.sleep(.25)
        if client.info("server")["redis_version"] != "7.4.10":
            raise RuntimeError("Unexpected Redis version")
        sampler = runner.Sampler(evidence / "process-resources.jsonl", client, resources["fileDescriptors"],
                                 resources["nativeThreads"], watch_swap=True)
        sampler.thread.start()
        flags = runner.lane_server_flags()
        flags.update({"xa.mass.redis.url": redis_url, "xa.mass.redis.scope": scope})
        env.update(XA_MASS_REDIS_URL=redis_url, XA_MASS_REDIS_SCOPE=scope, XA_MASS_KERNEL_PACER_PRESET="DEFAULT")
        jars = [p for p in (runner.ROOT / "server_boot_jvm/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
        if len(jars) != 1:
            raise RuntimeError("Expected exactly one packaged Boot JAR")
        jvm = lambda role: ["-Xms256m", f"-Xmx{resources[role + 'HeapMiB']}m", "-XX:+ExitOnOutOfMemoryError"]
        recording = ["-XX:FlightRecorderOptions=maxchunksize=8m",
                     f"-XX:StartFlightRecording=name=capacity,settings={runner.MODULE / 'lane-attribution.jfc'},"
                     f"filename={private / 'server.jfr'},maxsize=240m,dumponexit=true"]
        runner.write_json(evidence / "effective-config.json", dict(experiment=config, profile=profile, stage=stage,
            cpuIds=cpu_ids, serverOverrides=flags, jvmOptions={**{r: jvm(r) for r in ("server", "host")},
                "harness": [f"-Xmx{resources['harnessHeapMiB']}m", "-XX:+ExitOnOutOfMemoryError"]},
            appendBatch=100, appendConcurrency=16,
            mechanismConstants=runner.LANE_MECHANISM_CONSTANTS,
            redisContainer=runner.command(["docker", "inspect", container, "--format",
                                          "{{json .HostConfig.CpusetCpus}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}"])))
        launch("server", ["java", *jvm("server"), *recording, "-jar", jars[0], *(f"--{k}={v}" for k, v in flags.items())])
        runner.wait_http("http://127.0.0.1:18082/actuator/health/readiness", processes["server"], sampler,
                         min(deadline, time.monotonic() + 180))
        inventory = private / "data/scenario-workers"
        runner.materialize_inventory(inventory, {g: tuple({"runtime": "java", "capability": "string-utils"}
            for _ in range(profile["workersPerGroup"])) for g in runner.LANE_GROUPS})
        host_config = private / "worker-simulator.json"
        runner.write_json(host_config, dict(runtimeApiBaseUrl="http://127.0.0.1:18082", sandboxRoot=str(inventory.resolve()),
            controlPort=18086, workerGroups=runner.lane_host_groups(profile["workersPerGroup"])))
        launch("host", ["java", *jvm("host"), "-cp",
            runner.ROOT / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*",
            "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", host_config])
        runner.wait_http("http://127.0.0.1:18086/lab/v1/workers", processes["host"], sampler,
                         min(deadline, time.monotonic() + 180))
        phase("bootstrap")
        affinity = {role: sorted(os.sched_getaffinity(processes[role].pid)) for role in ("server", "host")}
        if any(actual != cpu_ids for actual in affinity.values()):
            raise RuntimeError("JVM CPU affinity differs from experiment profile")
        runner.write_json(evidence / "actual-affinity.json", affinity)
        world = evidence / "bootstrap/bootstrap.json"
        phase("quiesce", f"--world={world}")
        case = phase("case", f"--world={world}", f"--case=sat-{path}", f"--repetition={repetition}")
    except Exception as error:
        errors.append(str(error))
    finally:
        if sampler:
            try:
                sampler.stop()
            except Exception as error:
                errors.append("Sampler cleanup " + type(error).__name__)
            if sampler.failure:
                errors.append(sampler.failure)
        for process in reversed(tuple(processes.values())):
            try:
                runner.stop_process(process)
            except Exception as error:
                errors.append("Cleanup " + type(error).__name__)
        if client:
            try:
                client.close()
            except Exception as error:
                errors.append("Redis client cleanup " + type(error).__name__)
        if container:
            try:
                runner.command(["docker", "rm", "--force", container], timeout=20)
            except Exception as error:
                errors.append("Container cleanup " + type(error).__name__)
    raw = evidence / "case/case.json"
    if raw.is_file():
        try:
            runner.command(["prlimit", f"--nofile={resources['fileDescriptors']}:{resources['fileDescriptors']}",
                "--", "taskset", "--cpu-list", cpus, *harness_command(runner, "experiment-analyze", evidence, config_path,
                f"--input={raw}", f"--recording={private / 'server.jfr'}",
                f"--resources={evidence / 'process-resources.jsonl'}", heap=resources["harnessHeapMiB"])],
                timeout=max(.1, min(180, deadline - time.monotonic())))
            case = json.loads((evidence / "capacity-case.json").read_text())
        except Exception as error:
            errors.append("Analysis " + type(error).__name__)
    if errors:
        case.update(status="invalid", evidenceStatus="incomplete")
        case["invalidReasons"] = [*case.get("invalidReasons", []), *errors]
    case.update(profile=profile["name"], experimentStage=stage, path=path, repetition=repetition,
                cpuIds=cpu_ids, workersPerGroup=profile["workersPerGroup"])
    if (evidence / "process-resources.jsonl").is_file():
        case["resourcePeaks"] = runner.resource_peaks(evidence / "process-resources.jsonl")
    runner.write_json(evidence / "result.json", case)
    print(json.dumps({k: case.get(k) for k in ("experimentStage", "profile", "path", "repetition",
                                              "status", "completedPerSecond", "invalidReasons")}), flush=True)
    return case


def run(options, runner):
    if sys.platform != "linux":
        raise RuntimeError("Capacity experiment requires Linux / WSL")
    manifest = verify_bundle(runner.ROOT)
    if manifest is None and not options.skip_build:
        runner.build(runner.ROOT, harness=True)
    if manifest is not None and not options.skip_build:
        raise RuntimeError("Packaged experiments must use the bundled run.sh entry")
    # java -version writes stderr; capture it explicitly for the environment record.
    java = runner.subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip()
    if not re.search(r'version "21[.\"]', java):
        raise RuntimeError("Java 21 required")
    runner.command(["docker", "info", "--format", "{{.ServerVersion}}"])
    image_id = runner.command(["docker", "image", "inspect", runner.REDIS_IMAGE, "--format", "{{.Id}}"])
    if str(options.output_root.resolve()).startswith("/mnt/"):
        raise RuntimeError("Capacity evidence must be stored on the Linux filesystem")
    output = runner.fresh_output(options.output_root)
    evidence = output / "evidence"
    evidence.mkdir()
    runner.command(harness_command(runner, "experiment-config", evidence, options.experiment_config.resolve()))
    config_path = evidence / "experiment.json"
    config = json.loads(config_path.read_text())
    available = sorted(os.sched_getaffinity(0))
    environment = dict(os=platform.freedesktop_os_release(), kernel=platform.release(), java=java,
        availableCpuIds=available, redisImage=runner.REDIS_IMAGE, redisImageId=image_id, referenceHost=False,
        artifact=manifest or dict(sourceHead=runner.command(["git", "rev-parse", "HEAD"]),
                                 worktreeDirty=bool(runner.command(["git", "status", "--porcelain"]))),
        artifactSha256=runner.artifact_fingerprints(runner.ROOT,
            next(p for p in (runner.ROOT / "server_boot_jvm/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar"))),
        redisPythonVersion=__import__("redis").__version__,
        meminfo=Path("/proc/meminfo").read_text(), cpuModel=next((x.split(":", 1)[1].strip()
            for x in Path("/proc/cpuinfo").read_text().splitlines() if x.startswith("model name")), "unknown"))
    runner.write_json(evidence / "environment.json", environment)
    cases = []

    def report():
        runner.write_json(evidence / "cases.json", dict(cases=cases, environment=environment))
        runner.command(harness_command(runner, "experiment-report", evidence, config_path,
                                      f"--input={evidence / 'cases.json'}"))
        return json.loads((evidence / "experiment-summary.json").read_text())

    for profile in config["profiles"]:
        if profile["cpuCount"] > len(available):
            cases.append(dict(profile=profile["name"], experimentStage="screening", path="task-any", repetition=1,
                status="invalid", evidenceStatus="incomplete", invalidReasons=["insufficient-cpus"]))
        else:
            cases.append(run_case(runner, output / ("screen-" + profile["name"]), config_path, config, profile,
                                  "screening", "task-any", 1, available[:profile["cpuCount"]]))
        report()
    result = report()
    if result.get("selectedProfile"):
        profile = next(p for p in config["profiles"] if p["name"] == result["selectedProfile"])
        blocked = False
        for repetition in range(1, config["confirmation"]["anyRepetitions"] + 1):
            paths = ["task-any"]
            if repetition <= config["confirmation"]["targetedRepetitions"]:
                paths.append("task-targeted")
            for path in paths:
                case = run_case(runner, output / f"confirm-{repetition}-{path}", config_path, config, profile,
                                "confirmation", path, repetition, available[:profile["cpuCount"]])
                cases.append(case)
                result = report()
                if case["status"] != "passed":
                    blocked = True
                    break
            if blocked:
                break
    print(json.dumps({"targetStatus": result["targetStatus"], "evidence": str(evidence)}), flush=True)
    return 1 if result["targetStatus"] == "inconclusive" else 0
