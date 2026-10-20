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
import importlib.util

_local_path = Path(__file__).resolve().with_name("local_runtime.py")
_local_spec = importlib.util.spec_from_file_location(
    "preview_runtime_" + hashlib.sha256(str(_local_path).encode()).hexdigest()[:12], _local_path)
local_runtime = importlib.util.module_from_spec(_local_spec)
_local_spec.loader.exec_module(local_runtime)
LocalRun, REDIS_DEFAULT = local_runtime.LocalRun, local_runtime.REDIS_DEFAULT
child_environment = local_runtime.child_environment
parse_local_arguments, resolve = local_runtime.parse_arguments, local_runtime.resolve

import psutil
import redis

PRODUCT = Path(__file__).resolve().parent
_connections = threading.local()


def http(base, path, body=None, timeout=6, method=None, headers=None):
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
                           headers={"Content-Type": "application/json", **(headers or {})})
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
    subprocess.run([str(gradle), "--no-daemon", ":distribution:server:stagePreviewServer", ":distribution:server:stagePreviewFrontend",
                    ":distribution:server:stagePreviewHost",
                    "--console=plain"], cwd=repo, env=child_environment(), check=True)


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


class Preview(LocalRun):
    """Scenario-proof composition; shares the local process implementation with both CLIs."""
    def __init__(self, count=60, port=18500, redis_url=None, root=PRODUCT, output=None,
                 sandbox_root=None, seed=0, app_count=20, message_content_mode=None, message_settings=None,
                 server_overrides=None):
        validate_parameters(count, seed, port, sandbox_root, app_count)
        root = Path(root).resolve()
        inventory = Path(sandbox_root or root / "data/scenario-workers").resolve()
        if message_content_mode not in (None, "text", "lab-json"):
            raise ValueError("Unsupported message content mode")
        self.count, self.app_count, self.seed = count, app_count, seed
        self.message_content_mode, self.message_settings = message_content_mode, message_settings
        self.server_overrides = dict(server_overrides or {})
        self.scenarios, self.lab = ("sms", "messages", "app-checks"), "/lab/v1/sms"
        source = (root / "build.gradle").is_file()
        configuration = root / ("build/preview/config" if source else "config") / "application-preview.yaml"
        settings = dict(profile="preview", product=root, source=source, work=inventory.parent.parent,
                        scope="test_products_" + uuid.uuid4().hex, temporary_scope=True,
                        redis_url=redis_url or REDIS_DEFAULT, port=port, adapter_port=port + 3,
                        adapter="products-websocket", runtime={}, runtime_config=configuration,
                        groups=None, groups_file=None, android_count=0, android_device=None,
                        java_file=None, java_config={"sandboxRoot": str(inventory), "controlPort": port + 4})
        super().__init__(settings, output=output or root / "build/runs" / settings["scope"])

    def server_arguments(self):
        return super().server_arguments() + [
            f"--{key}={str(value).lower() if isinstance(value, bool) else value}"
            for key, value in self.server_overrides.items()
        ]

    def start(self):
        self.preflight()
        path = self.root / ("build/preview/host/config/products.json" if self.settings["source"]
                            else "worker-simulator/config/products.json")
        if not path.is_file():
            raise RuntimeError("Preview artifacts missing: Worker configuration")
        config = json.loads(path.read_text(encoding="utf-8"))
        config.update(runtimeApiBaseUrl=self.url, sandboxRoot=str(self.sandbox_root),
                      controlPort=self.port + 4, seed=self.seed)
        config["workerGroups"]["demo-sim"]["count"] = self.count
        for group in ("app-a-sim", "app-b-sim"):
            if self.app_count:
                config["workerGroups"][group]["count"] = self.app_count
            else:
                config["workerGroups"].pop(group)
        if self.message_settings is not None:
            config["messages"] = self.message_settings
        if self.message_content_mode is not None:
            for group in config["workerGroups"].values():
                if "extension.worker.message.send" in group.get("events", []):
                    group["messageContentMode"] = self.message_content_mode
        self.settings["java_config"] = config
        return super().start()


def main(arguments=None):
    args = parse_local_arguments(arguments, preview=True)
    settings = resolve(args, product=PRODUCT)
    run = LocalRun(settings)
    try:
        run.preflight()
        if args.build:
            build()
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        with run:
            print(f"Scenario Preview: {run.url}; Java Host: {run.host}; logs: {run.output}", flush=True)
            while True:
                run.check()
                time.sleep(1)
    except KeyboardInterrupt:
        return 0
    finally:
        run.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
