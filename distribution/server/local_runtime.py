"""Local process composition. Resource semantics remain with Server and Worker Owners."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import time
import urllib.request
import urllib.parse
import uuid

import psutil
import redis
import yaml

PRODUCT = Path(__file__).resolve().parent
REPOSITORY = PRODUCT.parents[1]
PROFILES = {
    "preview": (18500, 18503, "products-websocket", None),
    "consumer": (18182, 18183, "runtime-websocket", "profile_consumer"),
    "default": (18082, None, None, "profile_default"),
}
REDIS_DEFAULT = "redis://127.0.0.1:6379/15"


def child_environment(source=None):
    """Business configuration never comes from the caller's shell."""
    source = os.environ if source is None else source
    permitted = {
        "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
        "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA",
        "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES",
        "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME",
        "LANG", "LC_ALL", "TERM", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE",
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR",
    }
    return {key: value for key, value in source.items() if key.upper() in permitted}


def file_object(path, kind="JSON"):
    path = Path(path).resolve()
    if not path.is_file():
        raise ValueError(f"{kind} file does not exist: {path}")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate configuration key: {key}")
            result[key] = value
        return result
    if kind == "YAML":
        class Loader(yaml.SafeLoader):
            pass
        def mapping(loader, node):
            loader.flatten_mapping(node)
            return unique((loader.construct_object(k), loader.construct_object(v)) for k, v in node.value)
        Loader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
        try:
            value = yaml.load(path.read_text(encoding="utf-8-sig"), Loader=Loader)
        except yaml.YAMLError as error:
            raise ValueError(f"Invalid YAML file: {path}") from error
    else:
        value = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError(f"{kind} root must be an object: {path}")
    return value


def at(document, *keys, default=None):
    for key in keys:
        if not isinstance(document, dict):
            raise ValueError(f"Expected configuration object before {key}")
        prefix = key + "."
        dotted = {name[len(prefix):]: value for name, value in document.items()
                  if isinstance(name, str) and name.startswith(prefix)}
        if key in document:
            if dotted:
                raise ValueError(f"Do not mix dotted and nested configuration under {key}")
            document = document[key]
        elif dotted:
            document = dotted
        else:
            return default
    return document


def literal(value, name):
    if not isinstance(value, str) or not value.strip() or "${" in value:
        raise ValueError(f"{name} requires an explicit non-empty literal")
    return value


def port(value, name):
    if type(value) is not int or not 1 <= value <= 65535:
        raise ValueError(f"{name} must be an integer in 1..65535")
    return value


def parse_arguments(arguments=None, *, preview=False):
    parser = argparse.ArgumentParser(description="Start one local Server/Adapter with optional Java and Android Workers.")
    parser.add_argument("--profile", choices=tuple(PROFILES), default="preview")
    for name in ("runtime-config", "groups", "work-dir", "java-config"):
        parser.add_argument("--" + name, type=Path)
    for name in ("server-port", "adapter-port"):
        parser.add_argument("--" + name, type=int)
    parser.add_argument("--redis-url")
    parser.add_argument("--redis-scope")
    parser.add_argument("--java-count", action="append", default=[], metavar="GROUP=COUNT")
    parser.add_argument("--no-java-workers", action="store_true")
    parser.add_argument("--android-count", type=int, default=0, choices=range(4))
    parser.add_argument("--android-device")
    if preview:
        parser.add_argument("--build", action="store_true", help="Build checkout artifacts; unavailable in an extracted ZIP")
    args = parser.parse_args(arguments)
    if preview and args.profile != "preview":
        parser.error("The packaged Preview entry supports only preview")
    if args.no_java_workers and (args.java_config or args.java_count):
        parser.error("--no-java-workers cannot be combined with Java configuration/counts")
    if args.android_device and not args.android_count:
        parser.error("--android-device requires --android-count")
    return args


def resolve(args, *, product=PRODUCT):
    """Resolve process coordinates only; pass the original business YAML to Spring."""
    product = Path(product).resolve()
    source = (product / "build.gradle").is_file()
    runtime = file_object(args.runtime_config, "YAML") if args.runtime_config else {}
    if at(runtime, "spring", "config") is not None or at(runtime, "spring", "profiles") is not None:
        raise ValueError("Local Runtime YAML must not import hidden coordinates or select another profile")
    http_default, adapter_default, adapter_id, scope_default = PROFILES[args.profile]
    mass = at(runtime, "xa", "mass", default={})
    for name, value in (
        ("Task archive directory", at(mass, "task-retention", "directory")),
        ("Batch Checks configuration path", at(mass, "scenarios", "app-checks", "configuration", "path")),
    ):
        if value is not None and not Path(literal(value, name)).is_absolute():
            raise ValueError(f"{name} must be absolute")
    seed = at(mass, "scenarios", "app-checks", "configuration", "seed")
    if seed is not None:
        seed = literal(seed, "Batch Checks seed")
        if not seed.startswith("classpath:"):
            uri = urllib.parse.urlsplit(seed)
            path = Path(urllib.request.url2pathname(uri.path))
            if uri.scheme != "file" or not path.is_absolute() or not path.is_file():
                raise ValueError("Batch Checks seed requires an existing absolute file URI or classpath resource")
    server_port = port(args.server_port if args.server_port is not None else at(runtime, "server", "port", default=http_default), "server.port")
    adapter_id = at(mass, "worker-endpoints", "defaults", "WEBSOCKET", default=adapter_id)
    if adapter_id is not None:
        literal(adapter_id, "default WebSocket endpoint")
    configured_port = at(mass, "worker-delivery", "adapter", "instances", adapter_id, "listen-port", default=adapter_default) if adapter_id else None
    adapter_port = args.adapter_port if args.adapter_port is not None else configured_port
    if adapter_port is not None:
        port(adapter_port, "Adapter port")
        if not adapter_id:
            raise ValueError("Configure a default WebSocket Adapter before selecting its port")
    if adapter_port == server_port:
        raise ValueError("Server and Adapter ports must differ")
    for name, value in (("server.address", at(runtime, "server", "address")),
                        ("Adapter listen-host", at(mass, "worker-delivery", "adapter", "instances", adapter_id, "listen-host") if adapter_id else None)):
        if value not in (None, "127.0.0.1"):
            raise ValueError(f"{name}: local launch uses 127.0.0.1")
    redis_url = literal(args.redis_url if args.redis_url is not None else at(mass, "redis", "url", default=REDIS_DEFAULT), "Redis URL")
    scope = args.redis_scope if args.redis_scope is not None else at(mass, "redis", "scope", default=scope_default)
    if args.redis_scope is not None or "scope" in at(mass, "redis", default={}):
        literal(scope, "Redis scope")
    temporary_scope = scope is None
    scope = scope or "test_products_" + uuid.uuid4().hex
    if not re.fullmatch(r"[A-Za-z0-9_-]+", literal(scope, "Redis scope")):
        raise ValueError("Redis scope must contain only letters, digits, underscore and hyphen")
    work = (args.work_dir or (product if args.profile == "preview" else REPOSITORY)).resolve()
    groups = file_object(args.groups) if args.groups else None
    java_config = None
    java_file = args.java_config
    if not args.no_java_workers and (java_file or args.profile == "preview"):
        java_file = (java_file or (REPOSITORY / "worker_simulator_jvm/config/products.json" if source
                                 else product / "worker-simulator/config/products.json")).resolve()
        java_config = file_object(java_file)
        group_configs = java_config.get("workerGroups")
        if not isinstance(group_configs, dict):
            raise ValueError("Java workerGroups must be an object")
        if any(not isinstance(config, dict) for config in group_configs.values()):
            raise ValueError("Each Java Group configuration must be an object")
        counts = {}
        for override in args.java_count:
            group, separator, amount = override.partition("=")
            if not separator or group not in group_configs or group in counts:
                raise ValueError("--java-count requires a unique configured GROUP=COUNT")
            try:
                counts[group] = int(amount)
            except ValueError as error:
                raise ValueError("--java-count requires integer counts") from error
        if "startupPlan" in java_config and counts:
            raise ValueError("Explicit counts cannot replace a file's startupPlan")
        if "startupPlan" not in java_config:
            selected = {group: counts.get(group, config.get("count", 50)) for group, config in group_configs.items()}
            if any(type(n) is not int or not 0 <= n <= 15000 for n in selected.values()):
                raise ValueError("Java counts must be integers in 0..15000")
            if any(config.get("newEnvironment") is True for config in group_configs.values()):
                raise ValueError("Quantity launch preserves inventory; perform an explicit inventory rebuild separately")
            java_config["startupPlan"] = {"workerCounts": selected}
            if not any(selected.values()):
                java_config = None
        if java_config:
            java_config["runtimeApiBaseUrl"] = f"http://127.0.0.1:{server_port}"
            # An explicit environment directory owns inventory; otherwise honor file-relative paths.
            inventory = work / "data/scenario-workers"
            if args.java_config and not args.work_dir:
                inventory = Path(java_config.get("sandboxRoot", "./data/scenario-workers"))
                if not inventory.is_absolute():
                    inventory = java_file.parent / inventory
            inventory = inventory.resolve()
            if inventory.parts[-2:] != ("data", "scenario-workers"):
                raise ValueError("Java sandboxRoot must end with data/scenario-workers")
            java_config["sandboxRoot"] = str(inventory)
            java_config["controlPort"] = port(
                java_config.get("controlPort", 18086) if args.java_config else server_port + 4, "Java controlPort")
    elif args.java_count:
        raise ValueError("--java-count requires Java configuration")
    if (java_config or args.android_count) and adapter_port is None:
        raise ValueError("Workers require a configured default WebSocket Adapter")
    ports = [server_port] + ([adapter_port] if adapter_port else []) + ([java_config["controlPort"]] if java_config else [])
    if len(ports) != len(set(ports)):
        raise ValueError("Server, Adapter and Java Host ports must differ")
    return dict(profile=args.profile, runtime=runtime, runtime_config=args.runtime_config.resolve() if args.runtime_config else None,
                groups=groups, groups_file=args.groups.resolve() if args.groups else None, port=server_port,
                adapter_port=adapter_port, adapter=adapter_id, redis_url=redis_url, scope=scope,
                temporary_scope=temporary_scope, work=work, java_config=java_config, java_file=java_file,
                android_count=args.android_count, android_device=args.android_device, product=product, source=source,
                captured_runtime=True)


def check_ports(ports):
    for number in ports:
        with socket.socket() as probe:
            if os.name == "nt":
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                probe.bind(("127.0.0.1", number))
            except OSError as error:
                raise RuntimeError(f"Local port {number} is occupied; no existing process was stopped") from error


def http(base, path, body=None):
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=4) as response:
        return json.load(response)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stop_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


class Processes:
    def __init__(self, output, cwd):
        self.output, self.cwd = Path(output), Path(cwd)
        self.processes, self.logs, self.peaks = {}, [], {}

    def launch(self, name, args, env):
        self.output.mkdir(parents=True, exist_ok=True)
        stream = (self.output / f"{name}.log").open("wb")
        self.logs.append(stream)
        self.processes[name] = subprocess.Popen(args, cwd=self.cwd, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)

    def sample(self):
        for name, process in self.processes.items():
            try:
                observed = psutil.Process(process.pid)
                item = self.peaks.setdefault(name, {"rssBytes": 0, "threads": 0, "cpuSeconds": 0})
                item["rssBytes"] = max(item["rssBytes"], observed.memory_info().rss)
                item["threads"] = max(item["threads"], observed.num_threads())
                cpu = observed.cpu_times()
                item["cpuSeconds"] = cpu.user + cpu.system
            except psutil.NoSuchProcess:
                pass

    def check(self):
        for name, process in self.processes.items():
            if process.poll() is not None:
                raise RuntimeError(f"{name} exited ({process.returncode}); see {self.output / (name + '.log')}")
        self.sample()

    def wait_for(self, predicate, seconds, description):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.check()
            try:
                if predicate():
                    return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.2)
        raise RuntimeError("Timed out waiting for " + description)

    def close_processes(self):
        failures = []
        for name, process in reversed(list(self.processes.items())):
            try:
                stop_process(process)
            except (OSError, subprocess.TimeoutExpired) as error:
                failures.append(f"{name} PID {process.pid}: {error}")
        for stream in self.logs:
            stream.close()
        return failures


class LocalRun(Processes):
    def __init__(self, settings, *, output=None):
        self.settings = settings
        self.environment = child_environment()
        self.root = settings["product"]
        self.scope, self.redis_url = settings["scope"], settings["redis_url"]
        self.port, self.adapter = settings["port"], settings["adapter"]
        self.url = f"http://127.0.0.1:{self.port}"
        self.host = f"http://127.0.0.1:{settings['java_config']['controlPort']}" if settings["java_config"] else None
        self.sandbox_root = Path(settings["java_config"]["sandboxRoot"]) if settings["java_config"] else None
        self.app_checks_config = settings["work"] / "data/app-checks/configuration.json"
        super().__init__(output or settings["work"] / "build/runs" / uuid.uuid4().hex, settings["work"])
        self.task_archives = (self.output / "task-archives" if settings["temporary_scope"]
                              else settings["work"] / "data/task-archives")
        self.redis, self.android, self.closed, self.artifacts = None, None, False, {}

    def preflight(self):
        s = self.settings
        check_ports([self.port] + ([s["adapter_port"]] if s["adapter_port"] else [])
                    + ([s["java_config"]["controlPort"]] if s["java_config"] else []))
        java = shutil.which("java")
        if not java:
            raise RuntimeError("Java 21 is required on PATH")
        result = subprocess.run([java, "-version"], capture_output=True, text=True, check=True, env=child_environment())
        if not re.search(r'version "21[."]', result.stderr + result.stdout):
            raise RuntimeError("Java 21 is required")
        client = redis.Redis.from_url(self.redis_url, socket_timeout=3, socket_connect_timeout=3)
        try:
            if int(client.info("server")["redis_version"].split(".")[0]) < 7:
                raise RuntimeError("Redis 7 or later is required")
        except redis.RedisError as error:
            raise RuntimeError(f"Configured Redis is unavailable ({type(error).__name__})") from error
        finally:
            client.close()
        if s["android_count"]:
            if not s["source"]:
                raise ValueError("Packaged Preview starts packaged Java Workers; Android builds use the source entry")
            import sys
            sys.path.insert(0, str(REPOSITORY / "xa-android"))
            from local_workers import AndroidApps
            self.android = AndroidApps(s["android_count"], s["android_device"], self.url,
                                       s["adapter_port"], self.output, child_environment())
            occupied = {self.port, s["adapter_port"]}
            if s["java_config"]:
                occupied.add(s["java_config"]["controlPort"])
            if any(host_port in occupied for _, _, host_port in self.android.apps):
                raise ValueError("Android Host ports must differ from Server, Adapter and Java Host ports")
            self.android.preflight()

    def stage(self):
        s = self.settings
        source_root = self.root / "build/preview" if s["source"] else self.root
        server_lib = source_root / "server" if s["source"] else source_root / "lib"
        frontend = source_root / "frontend/dist"
        if self.android:
            server_lib = self.android.runtime_root / "lib"
            frontend = self.android.runtime_root / "frontend/dist"
        jars = [p for p in server_lib.glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        if len(jars) != 1:
            raise RuntimeError("Exactly one staged Server JAR is required")
        stage = self.output / "artifacts"
        stage.mkdir(parents=True, exist_ok=True)
        self.server_jar = stage / jars[0].name
        shutil.copy2(jars[0], self.server_jar)
        self.frontend = stage / "frontend"
        shutil.copytree(frontend, self.frontend)
        if s["java_config"]:
            host = source_root / ("host/lib" if s["source"] else "worker-simulator/lib")
            self.host_lib = stage / "host"
            shutil.copytree(host, self.host_lib)
        if s["runtime_config"] and s.get("captured_runtime"):
            self.frozen_runtime = self.output / "runtime.yaml"
            self.frozen_runtime.write_text(yaml.safe_dump(s["runtime"], allow_unicode=True, sort_keys=False), encoding="utf-8")
        self.artifacts = {"server": {"file": self.server_jar.name, "sha256": digest(self.server_jar)},
                          "hostClasspath": [{"file": p.name, "sha256": digest(p)} for p in sorted(self.host_lib.glob("*.jar"))] if s["java_config"] else []}
        frontend_hash = hashlib.sha256()
        for asset in sorted(p for p in self.frontend.rglob("*") if p.is_file()):
            frontend_hash.update(asset.relative_to(self.frontend).as_posix().encode() + b"\0")
            frontend_hash.update(bytes.fromhex(digest(asset)))
        self.artifacts["frontendSha256"] = frontend_hash.hexdigest()

    def server_arguments(self):
        s = self.settings
        command = [shutil.which("java"), "-XX:ActiveProcessorCount=8", "-XX:+ExitOnOutOfMemoryError", "-Xmx2g",
                   "-jar", str(self.server_jar), f"--spring.profiles.active={s['profile']}",
                   "--spring.config.location=classpath:/", "--server.address=127.0.0.1", f"--server.port={self.port}",
                   "--xa.mass.worker-delivery.adapter.remote-base-url=" + self.url,
                   "--spring.web.resources.static-locations=" + self.frontend.as_uri() + "/",
                   "--xa.mass.redis.url=${XA_MASS_LOCAL_REDIS_URL}", f"--xa.mass.redis.scope={self.scope}",
                   "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.buffered=false",
                   "--server.tomcat.accesslog.rotate=false", "--server.tomcat.accesslog.directory=" + str(self.output),
                   "--server.tomcat.accesslog.prefix=runtime-http", "--server.tomcat.accesslog.suffix=.log",
                   "--server.tomcat.accesslog.pattern=%m %U %s"]
        if s["runtime_config"]:
            command.append("--spring.config.additional-location=" + getattr(self, "frozen_runtime", s["runtime_config"]).as_uri())
        if s["groups"] is not None:
            command.append("--xa.mass.worker-assembly.group-config-json=" + json.dumps(s["groups"], separators=(",", ":")))
        if s["adapter_port"] is not None:
            command += [f"--xa.mass.worker-delivery.adapter.instances.{self.adapter}.listen-port={s['adapter_port']}",
                        f"--xa.mass.worker-delivery.adapter.instances.{self.adapter}.listen-host=127.0.0.1",
                        f"--xa.mass.worker-endpoints.endpoints.{self.adapter}.public-uri=ws://127.0.0.1:{s['adapter_port']}/api/v1/worker-delivery/websocket"]
        mass = at(s["runtime"], "xa", "mass", default={})
        archive = at(mass, "task-retention", "directory")
        if archive is None:
            command.append("--xa.mass.task-retention.directory=" + str(self.task_archives))
        elif not Path(literal(archive, "Task archive directory")).is_absolute():
            raise ValueError("External Task archive directory must be absolute")
        if s["profile"] == "preview" and at(mass, "scenarios", "app-checks", "configuration", "path") is None:
            command.append("--xa.mass.scenarios.app-checks.configuration.path=" + str(self.app_checks_config))
        return command

    def start(self):
        s = self.settings
        self.output.mkdir(parents=True, exist_ok=True)
        s["work"].mkdir(parents=True, exist_ok=True)
        self.redis = redis.Redis.from_url(self.redis_url, socket_timeout=3)
        if int(self.redis.info("server")["redis_version"].split(".")[0]) < 7:
            raise RuntimeError("Redis 7 or later is required")
        self.stage()
        self.launch("server", self.server_arguments(), self.server_process_environment())
        self.wait_for(lambda: http(self.url, "/actuator/health/readiness").get("status") == "UP", 90, "Server readiness")
        if s["java_config"]:
            self.worker_config_path = self.output / "worker-simulator.json"
            self.worker_config_path.write_text(json.dumps(s["java_config"], indent=2) + "\n", encoding="utf-8")
            self.launch("host", [shutil.which("java"), "-XX:ActiveProcessorCount=8", "-XX:+ExitOnOutOfMemoryError", "-Xmx1g",
                                "-cp", str(self.host_lib / "*"), "com.xa.mass.workersimulator.WorkerSimulatorMain",
                                "--config", str(self.worker_config_path)], dict(self.environment))
            self.wait_for(self.host_ready, 90, "Java Worker identities")
            self.wait_for(self.connected, 60, "Java Worker connections")
        if self.android:
            self.android.start()
            self.wait_for(lambda: self.network_connected(self.android.worker_ids()), 90, "Android Worker connections")
        self.write_record()
        return self

    def server_process_environment(self):
        env = dict(self.environment)
        # Explicit parent-to-child secret transport, never read from the invoking shell.
        env["XA_MASS_LOCAL_REDIS_URL"] = self.redis_url
        return env

    def host_ready(self):
        workers = http(self.host, "/lab/v1/workers")["workers"]
        plan = self.settings["java_config"].get("startupPlan", {})
        counts = plan.get("workerCounts")
        if counts is not None:
            return all(sum(w.get("runtimeState") == "RUNNING" and bool(w.get("workerId"))
                           for w in workers if w["workerGroupId"] == group) == count for group, count in counts.items())
        return all(w.get("workerId") and w.get("runtimeState") == "RUNNING" for w in workers)

    def network_connected(self, ids):
        if not ids:
            return False
        for offset in range(0, len(ids), 100):
            batch = ids[offset:offset + 100]
            states = http(self.url, f"/api/v1/runtime-view/endpoint-managers/{self.adapter}/workers:network-observe", batch).get("statesByWorkerId", {})
            if any(states.get(worker) != "connected" for worker in batch):
                return False
        return True

    def connected(self):
        workers = http(self.host, "/lab/v1/workers")["workers"]
        ids = [w["workerId"] for w in workers if w.get("runtimeState") == "RUNNING" and w.get("workerId")]
        return self.network_connected(ids)

    def write_record(self):
        s = self.settings
        record = dict(scope=self.scope, url=self.url, host=self.host, profile=s["profile"],
                      adapter=self.adapter, adapterPort=s["adapter_port"], artifacts=self.artifacts,
                      javaCounts=s["java_config"].get("startupPlan", {}).get("workerCounts",
                          {g: c.get("count", 50) for g, c in s["java_config"]["workerGroups"].items()}) if s["java_config"] else {},
                      seed=s["java_config"].get("seed", 0) if s["java_config"] else None,
                      androidCount=s["android_count"], pids={name: p.pid for name, p in self.processes.items()},
                      workDir=str(s["work"]), sandboxRoot=str(self.sandbox_root) if self.sandbox_root else None,
                      appChecksConfiguration=str(self.app_checks_config), temporaryScope=s["temporary_scope"],
                      runtimeConfig=str(s["runtime_config"]) if s["runtime_config"] else None,
                      groupsFile=str(s["groups_file"]) if s["groups_file"] else None)
        (self.output / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    def close(self):
        if self.closed:
            return
        self.closed = True
        failures = []
        if self.android:
            failures += self.android.close()
        failures += self.close_processes()
        if self.redis:
            try:
                if self.settings["temporary_scope"] and not failures:
                    if not re.fullmatch(r"test_products_[0-9a-f]{32}", self.scope):
                        raise RuntimeError("Refusing unsafe temporary scope cleanup")
                    prefix = f"xa_mass:{self.scope}:".encode()
                    batch = []
                    for key in self.redis.scan_iter(match=prefix + b"*", count=1000):
                        if not key.startswith(prefix):
                            raise RuntimeError("Unexpected cleanup key")
                        batch.append(key)
                        if len(batch) == 1000:
                            self.redis.unlink(*batch)
                            batch.clear()
                    if batch:
                        self.redis.unlink(*batch)
            except Exception as error:
                failures.append(f"Redis scope {self.scope}: {type(error).__name__}")
            finally:
                self.redis.close()
        if self.settings["temporary_scope"] and not failures and self.task_archives.exists():
            if self.task_archives.resolve().parent != self.output.resolve():
                failures.append("Task archive path is outside this run")
            else:
                try:
                    shutil.rmtree(self.task_archives)
                except OSError as error:
                    failures.append(f"Task archives {self.task_archives}: {error}")
        if failures:
            raise RuntimeError("Cleanup incomplete: " + "; ".join(failures))

    def __enter__(self):
        try:
            return self.start()
        except BaseException:
            self.close()
            raise

    def __exit__(self, *_):
        self.close()
