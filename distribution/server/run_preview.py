"""Run the shared Scenario Preview from the checkout or an extracted ZIP."""
from __future__ import annotations

import argparse
import hashlib
import http.client as http_client
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import time
import threading
import urllib.error
import urllib.request
import urllib.parse
import uuid

import psutil
import redis

PRODUCT = Path(__file__).resolve().parent
_connections = threading.local()


def http(base, path, body=None, timeout=6, method=None):
    # One connection per calling thread and local endpoint. Never retry mutations.
    if not hasattr(_connections, "items"):
        _connections.items = {}
    address = urllib.parse.urlsplit(base)
    cached = _connections.items.get(base)
    # A lifecycle proof may leave the Lab idle beyond its server keep-alive window.
    # Retire idle connections before the next request, without replaying any request.
    if cached is not None and time.monotonic() - cached[1] >= 5:
        cached[0].close()
        _connections.items.pop(base)
        cached = None
    connection = cached[0] if cached is not None else None
    if connection is None:
        connection = http_client.HTTPConnection(address.hostname, address.port, timeout=timeout)
    connection.timeout = timeout
    if connection.sock:
        connection.sock.settimeout(timeout)
    try:
        connection.request(method or ("GET" if body is None else "POST"), path,
                           body=None if body is None else json.dumps(body).encode(),
                           headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        raw = response.read()
        if response.status >= 400:
            raise urllib.error.HTTPError(base + path, response.status, response.reason, response.headers, io.BytesIO(raw))
        _connections.items[base] = (connection, time.monotonic())
        return json.loads(raw)
    except Exception:
        connection.close()
        _connections.items.pop(base, None)
        raise



def all_pages(base, path):
    items = []
    while True:
        page = http(base, path + f"?offset={len(items)}&limit=1000")
        items.extend(page["items"])
        if len(items) >= page["total"]:
            return items
        if not page["items"]:
            raise RuntimeError("Pagination stopped before total")



def build():
    if not (PRODUCT / "build.gradle").is_file():
        raise RuntimeError("An extracted Preview does not support --build; use its packaged artifacts")
    repo = PRODUCT.parents[1]
    gradle = repo / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradle), ":distribution:server:stagePreviewServer", ":distribution:server:stagePreviewFrontend",
                    ":distribution:server:stagePreviewHost",
                    "--console=plain"], cwd=repo, check=True)


def validate_parameters(count, seed, port, sandbox_root=None, app_count=20):
    if type(count) is not int or not 1 <= count <= 10_000:
        raise ValueError("count must be an integer between 1 and 10000")
    if type(seed) is not int or not -(2**63) <= seed < 2**63:
        raise ValueError("seed must be a signed 64-bit integer")
    if type(app_count) is not int or not 0 <= app_count <= 15_000:
        raise ValueError("app-count must be an integer between 0 and the Host Group limit of 15000")
    if type(port) is not int or not 1 <= port <= 65_531:
        raise ValueError("port must be an integer between 1 and 65531 (Adapter +3 and Host +4)")
    if sandbox_root is not None and Path(*Path(sandbox_root).resolve().parts[-2:]) != Path("data/scenario-workers"):
        raise ValueError("sandbox-root must end with data/scenario-workers")


class Preview:
    def __init__(self, count=60, port=18500, redis_url=None, root=PRODUCT, output=None, sandbox_root=None, seed=0, app_count=20):
        validate_parameters(count, seed, port, sandbox_root, app_count)
        self.root = Path(root).resolve()
        self.sandbox_root = Path(sandbox_root or self.root / "data" / "scenario-workers").resolve()
        self.count = count
        self.app_count = app_count
        self.seed = seed
        self.scenarios = ("sms", "messages", "app-checks")
        self.adapter = "products-websocket"
        self.lab = "/lab/v1/sms"
        self.port = port
        self.url = f"http://127.0.0.1:{port}"
        self.host = f"http://127.0.0.1:{port + 4}"
        self.redis_url = redis_url or os.environ.get("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15")
        self.scope = "test_products_" + uuid.uuid4().hex
        self.output = Path(output or self.root / "build" / "runs" / self.scope).resolve()
        self.processes = {}
        self.logs = []
        self.redis = None
        self.peaks = {}
        self.artifacts = {}
        self.closed = False

    def start(self):
        self.output.mkdir(parents=True, exist_ok=True)
        self.redis = redis.Redis.from_url(self.redis_url, socket_timeout=3)
        if int(self.redis.info("server")["redis_version"].split(".")[0]) < 7:
            raise RuntimeError("Redis 7 or later is required")
        # Fail before starting any JVM if a requested port is occupied.
        for port in (self.port, self.port + 3, self.port + 4):
            with socket.socket() as probe:
                if os.name == "nt":
                    probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                probe.bind(("127.0.0.1", port))
        java = shutil.which("java")
        if java is None:
            raise RuntimeError("Java 21 is required on PATH")
        version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
        if not re.search(r'version "21[.\"]', version.stderr + version.stdout):
            raise RuntimeError("Use Java 21 on PATH")
        source_layout = (self.root / "build.gradle").is_file()
        if not source_layout:
            server_lib = self.root / "lib"
            host_lib = self.root / "worker-simulator" / "lib"
            platform_frontend = self.root / "frontend" / "dist"
            configuration = self.root / "config"
        else:
            staged = self.root / "build" / "preview"
            server_lib = staged / "server"
            host_lib = staged / "host" / "lib"
            platform_frontend = staged / "frontend" / "dist"
            configuration = staged / "config"
        server_jars = sorted(p for p in server_lib.glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar"))
        if len(server_jars) != 1 or not host_lib.is_dir() or not platform_frontend.is_dir():
            raise RuntimeError("Preview artifacts missing; exactly one Server JAR is required. Build the source or use a complete ZIP")
        server_jar = server_jars[0]
        self.artifacts = {
            "server": {"file": server_jar.name, "sha256": hashlib.sha256(server_jar.read_bytes()).hexdigest()},
            "hostClasspath": [{"file": jar.name, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
                                   for jar in sorted(host_lib.glob("*.jar"))],
        }
        frontend_hash = hashlib.sha256()
        for artifact in sorted(p for p in platform_frontend.rglob("*") if p.is_file()):
            frontend_hash.update(artifact.relative_to(platform_frontend).as_posix().encode() + b"\0")
            frontend_hash.update(hashlib.sha256(artifact.read_bytes()).digest())
        self.artifacts["frontendSha256"] = frontend_hash.hexdigest()
        env = os.environ.copy()
        env.update(XA_MASS_REDIS_SCOPE=self.scope, XA_MASS_REDIS_URL=self.redis_url,
                   PREVIEW_SERVER_PORT=str(self.port),
                   PREVIEW_ADAPTER_PORT=str(self.port + 3))
        options = [java, "-XX:ActiveProcessorCount=8", "-XX:+ExitOnOutOfMemoryError"]
        self.launch("server", options + ["-Xmx2g", "-jar", str(server_jar),
                    "--spring.profiles.active=preview",
                    "--spring.config.additional-location=" + configuration.as_uri() + "/",
                    "--spring.web.resources.static-locations=" + platform_frontend.as_uri() + "/",
                    "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.buffered=false",
                    "--server.tomcat.accesslog.rotate=false", "--server.tomcat.accesslog.directory=" + str(self.output),
                    "--server.tomcat.accesslog.prefix=runtime-http", "--server.tomcat.accesslog.suffix=.log",
                    "--server.tomcat.accesslog.pattern=%m %U %s"], env)
        self.wait_for(lambda: http(self.url, "/actuator/health"), 60, "Server")
        for scenario in self.scenarios:
            self.wait_for(lambda scenario=scenario: http(self.url, "/api/v1/" + scenario + "/catalog"), 60, scenario + " initialization")
        self.worker_config_path = self.output / "worker-simulator.json"
        worker_config = json.loads((host_lib.parent / "config" / "products.json").read_text(encoding="utf-8"))
        worker_config.update(runtimeApiBaseUrl=self.url, sandboxRoot=str(self.sandbox_root), controlPort=self.port + 4,
                             seed=self.seed)
        group = worker_config["workerGroups"]["demo-sim"]
        group["count"] = self.count
        for app_group in ("app-a-sim", "app-b-sim"):
            if self.app_count == 0:
                # Exclude the Group entirely: count=0 must not reopen a retained inventory.
                worker_config["workerGroups"].pop(app_group)
            else:
                worker_config["workerGroups"][app_group]["count"] = self.app_count
        self.worker_config_path.write_text(json.dumps(worker_config, indent=2) + "\n", encoding="utf-8")
        self.launch("host", options + ["-Xmx1g", "-cp", str(host_lib / "*"),
                    "com.xa.mass.workersimulator.WorkerSimulatorMain",
                    "--config", str(self.worker_config_path)], env)
        self.wait_for(self.host_ready, 90, "Host identities")
        self.wait_for(self.connected, 60, "verified WebSocket routes")
        (self.output / "run.json").write_text(json.dumps({"scope": self.scope, "url": self.url, "host": self.host,
                "count": self.count, "appCount": self.app_count, "seed": self.seed, "sandboxRoot": str(self.sandbox_root), "scenarios": self.scenarios, "artifacts": self.artifacts,
                "pids": {k: p.pid for k, p in self.processes.items()}}, indent=2), encoding="utf-8")
        return self

    def host_ready(self):
        # This endpoint is admitted only after Host startup and covers every configured Group.
        inventory = http(self.host, "/lab/v1/workers")["workers"]
        return all(worker.get("workerId") and worker.get("runtimeState") == "RUNNING" for worker in inventory)

    def connected(self):
        inventory = http(self.host, "/lab/v1/workers")["workers"]
        for offset in range(0, len(inventory), 100):
            ids = [sim["workerId"] for sim in inventory[offset:offset + 100]]
            response = http(self.url, f"/api/v1/runtime-view/endpoint-managers/{self.adapter}/workers:network-observe", ids)
            states = response.get("statesByWorkerId", {})
            if any(states.get(worker) != "connected" for worker in ids):
                return False
        return True

    def launch(self, name, args, env):
        stream = (self.output / f"{name}.log").open("wb")
        self.logs.append(stream)
        self.processes[name] = subprocess.Popen(args, cwd=self.root, env=env, stdout=stream, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)

    def check(self):
        for name, process in self.processes.items():
            if process.poll() is not None:
                raise RuntimeError(f"{name} exited ({process.returncode}); see {self.output / (name + '.log')}")
        self.sample()

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

    def close(self):
        if self.closed:
            return
        self.closed = True
        for process in reversed(list(self.processes.values())):
            if process.poll() is None:
                process.terminate()
        deadline = time.monotonic() + 10
        for process in self.processes.values():
            try:
                process.wait(timeout=max(.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for stream in self.logs:
            stream.close()
        if self.redis is not None:
            if not re.fullmatch(r"test_products_[0-9a-f]{32}", self.scope):
                raise RuntimeError("Refusing unsafe Redis scope cleanup")
            prefix = f"xa_mass:{self.scope}:"
            try:
                batch = []
                for key in self.redis.scan_iter(match=prefix + "*", count=1000):
                    if not key.startswith(prefix.encode()):
                        raise RuntimeError("Unexpected cleanup key")
                    batch.append(key)
                    if len(batch) == 1000:
                        self.redis.unlink(*batch); batch.clear()
                if batch:
                    self.redis.unlink(*batch)
            finally:
                self.redis.close()

    def __enter__(self):
        try:
            return self.start()
        except BaseException:
            self.close()
            raise

    def __exit__(self, *_):
        self.close()


def parse_arguments(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--count", type=int, default=60, help="demo-sim Workers to initialize (1..10000); not a country quota")
    parser.add_argument("--app-count", type=int, default=20, help="Workers per App Group (0..15000); zero excludes both App Groups")
    parser.add_argument("--seed", type=int, default=0, help="Signed 64-bit initialization seed; existing inventory is reused")
    parser.add_argument("--sandbox-root", type=Path, help="Persistent inventory root ending in data/scenario-workers")
    parser.add_argument("--port", type=int, default=18500, help="Server base port; Adapter +3 and Host +4")
    args = parser.parse_args(arguments)
    try:
        validate_parameters(args.count, args.seed, args.port, args.sandbox_root, args.app_count)
    except ValueError as error:
        parser.error(str(error))
    return args


def main(arguments=None):
    args = parse_arguments(arguments)
    if args.build:
        build()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        with Preview(args.count, args.port, sandbox_root=args.sandbox_root, seed=args.seed, app_count=args.app_count) as run:
            print(f"Scenario Preview 0.1.0-preview: {run.url}/app-checks, /messages or /sms\nSimulator: {run.host}/lab\nPress Ctrl+C to end this run.", flush=True)
            while True:
                run.check()
                time.sleep(1)
    except KeyboardInterrupt:
        print("Preview stopped; owned processes and this run's Redis scope cleaned.")
    return 0


if __name__ == "__main__":
    main()
