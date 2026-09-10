"""Run the shared product preview from the checkout or an extracted preview ZIP."""
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


def http(base, path, body=None, timeout=6):
    # One connection per calling thread and local endpoint. Never retry mutations.
    if not hasattr(_connections, "items"):
        _connections.items = {}
    address = urllib.parse.urlsplit(base)
    connection = _connections.items.get(base)
    if connection is None:
        connection = http_client.HTTPConnection(address.hostname, address.port, timeout=timeout)
        _connections.items[base] = connection
    connection.timeout = timeout
    if connection.sock:
        connection.sock.settimeout(timeout)
    try:
        connection.request("GET" if body is None else "POST", path,
                           body=None if body is None else json.dumps(body).encode(),
                           headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        raw = response.read()
        if response.status >= 400:
            raise urllib.error.HTTPError(base + path, response.status, response.reason, response.headers, io.BytesIO(raw))
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
    repo = PRODUCT.parents[1]
    gradle = repo / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradle), ":distribution:product-preview:stageServer", ":distribution:product-preview:stageFrontend",
                    ":distribution:product-preview:stageHost",
                    "--console=plain"], cwd=repo, check=True)


class Preview:
    def __init__(self, counts=(20, 20, 20), port=18500, redis_url=None, root=PRODUCT, output=None, products="sms,messages"):
        self.root = Path(root).resolve()
        self.counts = counts
        if products not in ("sms", "messages", "sms,messages"):
            raise ValueError("Unknown product combination")
        self.products = products
        self.scenario = "products" if products == "sms,messages" else products
        self.adapter = "products-websocket"
        self.lab = "/lab/v1/sms" if "sms" in products else "/lab/v1/messages"
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
        packaged = (self.root / "lib").is_dir()
        if packaged:
            server_lib = self.root / "lib"
            host_lib = self.root / "scenario-workers" / "lib"
            platform_frontend = self.root / "frontend" / "dist"
        else:
            server_lib = self.root / "build" / "server"
            host_lib = self.root / "build" / "host" / "lib"
            platform_frontend = self.root / "build" / "frontend" / "dist"
        server_jars = sorted(p for p in server_lib.glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar"))
        if len(server_jars) != 1 or not host_lib.is_dir() or not platform_frontend.is_dir():
            raise RuntimeError("Build product artifacts first, or run with --build; exactly one Server JAR is required")
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
                    "--spring.profiles.active=" + ",".join(["product-preview"] + (["sms-reception"] if "sms" in self.products else []) + (["message-campaigns"] if "messages" in self.products else [])),
                    "--spring.config.additional-location=" + (self.root / "config").as_uri() + "/",
                    "--spring.web.resources.static-locations=" + platform_frontend.as_uri() + "/"], env)
        self.wait_for(lambda: http(self.url, "/actuator/health"), 60, "Server")
        for product in ("sms", "messages"):
            if product in self.products:
                self.wait_for(lambda product=product: http(self.url, "/api/v1/" + product + "/catalog"), 60, product + " initialization")
        self.launch("host", options + ["-Xmx1g", "-cp", str(host_lib / "*"),
                    "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain", "--scenario=" + self.scenario,
                    "--runtime-api-base-url=" + self.url, "--control-port=" + str(self.port + 4),
                    ("--sms-counts=" if self.scenario == "sms" else "--device-counts=") + ",".join(map(str, self.counts))], env)
        self.wait_for(lambda: http(self.host, self.lab + "/health").get("prepared") == sum(self.counts), 90, "Host identities")
        self.wait_for(self.connected, 60, "verified WebSocket routes")
        (self.output / "run.json").write_text(json.dumps({"scope": self.scope, "url": self.url, "host": self.host,
                "counts": self.counts, "products": self.products, "artifacts": self.artifacts,
                "pids": {k: p.pid for k, p in self.processes.items()}}, indent=2), encoding="utf-8")
        return self

    def connected(self):
        inventory = all_pages(self.host, self.lab + "/inventory")
        for offset in range(0, len(inventory), 100):
            ids = [sim["workerId"] for sim in inventory[offset:offset + 100]]
            response = http(self.url, "/api/v1/runtime-view/endpoint-managers/products-websocket/workers:network-observe", ids)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--counts", default="20,20,20", help="CN,US,GB counts")
    parser.add_argument("--port", type=int, default=18500, help="Server base port; Adapter +3 and Host +4")
    parser.add_argument("--products", choices=["sms", "messages", "sms,messages"], default="sms,messages")
    args = parser.parse_args()
    if args.build:
        build()
    counts = tuple(map(int, args.counts.split(",")))
    if len(counts) != 3 or min(counts) < 1 or sum(counts) > 10_000:
        parser.error("counts must specify three positive pools, at most 10,000 total")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        with Preview(counts, args.port, products=args.products) as run:
            print(f"Product Preview 0.1.0-preview: {run.url}/messages or /sms\nSimulator: {run.host}/lab\nPress Ctrl+C to end this run.", flush=True)
            while True:
                run.check()
                time.sleep(1)
    except KeyboardInterrupt:
        print("Preview stopped; owned processes and this run's Redis scope cleaned.")


if __name__ == "__main__":
    main()
