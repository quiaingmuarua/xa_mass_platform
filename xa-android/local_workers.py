"""Local Android App lifecycle, separate from the destructive device proof choreography."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.parse

import run_consumer as consumer


class AndroidApps:
    def __init__(self, count, serial, runtime_url, adapter_port, output, environment):
        self.count, self.serial = count, serial
        self.runtime_url, self.adapter_port = runtime_url, adapter_port
        self.output, self.environment = Path(output), environment
        self.apps = [(f"com.xa.mass.integration.androidworker.lab{i}",
                      f"xa-mass-android-worker-demo-lab{i}.apk", 18183 + i) for i in range(1, count + 1)]
        self.started, self.mappings = [], []
        self.adb_path = None

    def adb(self, *arguments, check=True, timeout=30):
        result = subprocess.run([self.adb_path, "-s", self.serial, *arguments], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout,
                                env=self.environment)
        if check and result.returncode:
            raise RuntimeError(f"ADB {arguments[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
        return result.stdout.strip()

    def preflight(self):
        self.adb_path = shutil.which("adb")
        if not self.adb_path:
            sdk = self.environment.get("ANDROID_HOME") or self.environment.get("ANDROID_SDK_ROOT")
            candidate = Path(sdk or "") / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if candidate.is_file():
                self.adb_path = str(candidate.resolve())
        if not self.adb_path:
            raise RuntimeError("ADB was not found on PATH or in the selected Android SDK")
        result = subprocess.run([self.adb_path, "devices"], capture_output=True, text=True, check=True,
                                env=self.environment, timeout=20)
        devices = [row.split()[0] for row in result.stdout.splitlines() if len(row.split()) == 2 and row.split()[1] == "device"]
        if self.serial is None:
            if len(devices) != 1:
                raise ValueError("Select --android-device when there is not exactly one available device")
            self.serial = devices[0]
        if self.serial not in devices:
            raise ValueError("Selected Android device is unavailable or unauthorized")
        for package, _, _ in self.apps:
            if self.adb("shell", "pidof", package, check=False):
                raise RuntimeError(f"Android App already running: {package}; it was not stopped")
        self.check_forward_ports()

    def check_forward_ports(self):
        forwarded = {}
        for row in self.adb("forward", "--list").splitlines():
            fields = row.split()
            if len(fields) == 3:
                forwarded[fields[1]] = fields
        for _, _, number in self.apps:
            entry = forwarded.get(f"tcp:{number}")
            if entry is not None:
                if entry[0] != self.serial:
                    raise RuntimeError(f"Android Host port {number} belongs to another device")
                continue
            with socket.socket() as probe:
                if os.name == "nt":
                    probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                try:
                    probe.bind(("127.0.0.1", number))
                except OSError as error:
                    raise RuntimeError(f"Android Host port {number} is occupied") from error

    def prepare(self, repository, version):
        runtime = repository / f"distribution/server/build/distributions/xa-mass-server-runtime-{version}.zip"
        sdk = repository / f"distribution/worker-sdk/build/distributions/xa-mass-worker-sdk-{version}.zip"
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
        runtime_hash, sdk_hash = consumer.digest(runtime), consumer.digest(sdk)
        # The existing consumer remains the single ZIP validator and materializer.
        receipt = consumer.prepare_archives(runtime, sdk, version, commit, runtime_hash, sdk_hash,
                                            self.output / "android-installation")
        self.runtime_root = Path(receipt["runtimeRoot"])
        fingerprint = hashlib.sha256()
        fingerprint.update(json.dumps([runtime_hash, sdk_hash, self.runtime_url]).encode())
        source = repository / "xa-android"
        for path in sorted(source.rglob("*")):
            relative = path.relative_to(source)
            build_input = relative.parts[0] in {"worker-demo", "capabilities", "gradle"} or relative.as_posix() in {
                "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "run_consumer.py"}
            if build_input and path.is_file() and not set(relative.parts) & {"build", ".gradle", "__pycache__", ".runtime"}:
                fingerprint.update(relative.as_posix().encode() + b"\0")
                fingerprint.update(bytes.fromhex(consumer.digest(path)))
        fingerprint.update(bytes.fromhex(consumer.digest(repository / ".github/scripts/build_android_consumer.py")))
        self.apk_root = self.output.parent.parent / "android-cache" / fingerprint.hexdigest()
        provenance_path = self.apk_root / "apk-provenance.json"
        if provenance_path.is_file():
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
            valid = (provenance.get("runtimeApiBaseUrl") == self.runtime_url
                     and all((self.apk_root / name).is_file()
                             and consumer.digest(self.apk_root / name) == value
                             for name, value in provenance.get("apkSha256", {}).items())
                     and all(name in provenance.get("apkSha256", {}) for _, name, _ in self.apps))
            if not valid:
                raise RuntimeError(f"Cached Android artifacts failed verification: {self.apk_root}")
            print(f"Reusing checked Android APKs: {self.apk_root}", flush=True)
        else:
            command = [sys.executable, str(repository / ".github/scripts/build_android_consumer.py"),
                       "--runtime-archive", str(runtime), "--sdk-archive", str(sdk),
                       "--version", version, "--commit", commit, "--runtime-sha256", runtime_hash,
                       "--sdk-sha256", sdk_hash, "--output-dir", str(self.apk_root),
                       "--runtime-api-base-url", self.runtime_url]
            subprocess.run(command, cwd=repository, env=self.environment, check=True)
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        self.provenance = provenance
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output / "android.json").write_text(json.dumps({
            "device": self.serial, "packages": [p for p, _, _ in self.apps],
            "runtimeApiBaseUrl": self.runtime_url, "apkDirectory": str(self.apk_root),
            "apkSha256": {n: provenance["apkSha256"][n] for _, n, _ in self.apps},
            "runtimeSha256": runtime_hash, "sdkSha256": sdk_hash, "gitCommit": commit,
        }, indent=2) + "\n", encoding="utf-8")

    def mapping_table(self, direction):
        rows = self.adb(direction, "--list").splitlines()
        result = {}
        for row in rows:
            parts = row.split()
            if len(parts) == 3 and (direction == "reverse" or parts[0] == self.serial):
                result[parts[1]] = parts[2]
        return result

    def map_port(self, direction, number):
        local = f"tcp:{number}"
        previous = self.mapping_table(direction).get(local)
        self.mappings.append((direction, local, previous))
        self.adb(direction, local, local)

    def installed_digest(self, package):
        paths = self.adb("shell", "pm", "path", package, check=False).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            return None
        output = self.adb("shell", "sha256sum", paths[0][8:], check=False)
        return output.split()[0] if output else None

    def start(self):
        # Recheck after a potentially lengthy build, before replacing any APK.
        for package, _, _ in self.apps:
            if self.adb("shell", "pidof", package, check=False):
                raise RuntimeError(f"Android App became active during build: {package}")
        self.check_forward_ports()
        for package, name, _ in self.apps:
            if self.installed_digest(package) != self.provenance["apkSha256"][name]:
                self.adb("install", "-r", str(self.apk_root / name), timeout=150)
        self.map_port("reverse", urllib.parse.urlsplit(self.runtime_url).port)
        self.map_port("reverse", self.adapter_port)
        for package, _, host_port in self.apps:
            self.map_port("forward", host_port)
            # Record before sending: an interrupted start command may already have launched the App.
            self.started.append(package)
            self.adb("shell", "am", "start", "-W", "-n",
                     f"{package}/com.xa.mass.android.workerdemo.MainActivity")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                if len(self.worker_ids()) == self.count:
                    return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.3)
        raise RuntimeError("Android Worker identities did not become ready")

    def host_call(self, port, event):
        request = urllib.request.Request(f"http://127.0.0.1:{port}/events/{event}:call",
                                         data=b"{}", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=3) as response:
            envelope = json.load(response)
        if envelope.get("status") != "succeeded":
            raise RuntimeError("Android Host event failed")
        return envelope["result"]

    def worker_ids(self):
        ids = []
        for _, _, host_port in self.apps:
            state = self.host_call(host_port, "extension.worker.android-demo.host.snapshot")
            if state.get("state") != "RUNNING" or not state.get("workerId"):
                return []
            ids.append(state["workerId"])
        if len(set(ids)) != self.count:
            raise RuntimeError("Android Apps did not receive distinct Worker identities")
        return ids

    def close(self):
        failures = []
        for package in reversed(self.started):
            try:
                self.adb("shell", "am", "force-stop", package)
            except Exception as error:
                failures.append(f"Android package {package}: {error}")
        for direction, local, previous in reversed(self.mappings):
            try:
                if self.mapping_table(direction).get(local) not in (None, local):
                    failures.append(f"ADB {direction} {local} was changed externally")
                    continue
                if previous is None:
                    if local in self.mapping_table(direction):
                        self.adb(direction, "--remove", local)
                else:
                    self.adb(direction, local, previous)
            except Exception as error:
                failures.append(f"ADB {direction} {local}: {error}")
        self.started.clear()
        self.mappings.clear()
        return failures
