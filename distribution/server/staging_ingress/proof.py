"""Real Nginx + verified test-CA HTTPS/WSS proof; no Redis or deployment access.

Gateway is also usable by the existing Runtime Distribution/SDK proof with real
loopback Runtime/Adapter upstream ports. Only the fixture's own process is stopped.
"""

from __future__ import annotations

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import AbstractContextManager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import select
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time

from .renderer import Deployment, render

ADMIN = ("stage-admin", "fixture-admin-password")
WORKER = ("host-a", "fixture-worker-a-password")
OTHER_WORKER = ("host-b", "fixture-worker-b-password")
THIRD_WORKER = ("host-c", "fixture-worker-c-password")


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def basic(credential: tuple[str, str]) -> str:
    return "Basic " + base64.b64encode(":".join(credential).encode("ascii")).decode("ascii")


def _read_exact(stream, count: int) -> bytes:
    result = b""
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise EOFError("proof WebSocket closed before its complete frame")
        result += chunk
    return result


class Gateway(AbstractContextManager):
    """A real Nginx fixture, accepting existing Runtime/Adapter upstream ports.

    Public properties: config, ca_file, certificate, admin_url, worker_url,
    websocket_url, admin_password_file, worker_password_file. The caller may use
    the supplied CA in a Java truststore. Never disable TLS verification.
    """

    def __init__(self, directory: Path, runtime_port: int, adapter_port: int, *,
                 nginx: str = "nginx", openssl: str = "openssl",
                 worker_groups: tuple[str, ...] = ("staging-workers",),
                 http_port: int | None = None, https_port: int | None = None):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        if any(self.directory.iterdir()):
            raise ValueError("Gateway fixture requires a fresh empty directory")
        self.nginx = shutil.which(nginx) or str(Path(nginx).resolve())
        self.openssl = shutil.which(openssl) or str(Path(openssl).resolve())
        if not Path(self.nginx).is_file() or not Path(self.openssl).is_file():
            raise RuntimeError("Real ingress proof requires nginx and openssl executables")
        self.ca_file = self.directory / "test-ca.pem"
        self.certificate = self.directory / "server.pem"
        self.admin_password_file = self.directory / "admin.htpasswd"
        self.worker_password_file = self.directory / "workers.htpasswd"
        self._certificates()
        self.write_passwords(self.admin_password_file, (ADMIN,))
        self.write_passwords(self.worker_password_file, (WORKER, OTHER_WORKER, THIRD_WORKER))
        state = self.directory / "state"
        for name in ("client_temp", "proxy_temp", "fastcgi_temp", "uwsgi_temp", "scgi_temp"):
            (state / name).mkdir(parents=True, exist_ok=True)
        (self.directory / "acme" / ".well-known" / "acme-challenge").mkdir(parents=True, exist_ok=True)
        self.config = Deployment(
            str(self.certificate), str(self.directory / "server.key"),
            str(self.admin_password_file), str(self.worker_password_file),
            str(self.directory / "acme"), str(state), worker_groups,
            admin_host="localhost", worker_host="127.0.0.1", listen_host="127.0.0.1",
            http_port=http_port or free_port(), https_port=https_port or free_port(),
            runtime_port=runtime_port, adapter_port=adapter_port,
        )
        self.configuration = self.directory / "nginx.conf"
        self.configuration.write_text(render(self.config), encoding="utf-8")
        self.context = ssl.create_default_context(cafile=str(self.ca_file))
        self.process: subprocess.Popen | None = None
        self._log = None

    @property
    def admin_url(self) -> str:
        return f"https://localhost:{self.config.https_port}"

    @property
    def worker_url(self) -> str:
        return f"https://127.0.0.1:{self.config.https_port}"

    @property
    def websocket_url(self) -> str:
        return self.worker_url.replace("https:", "wss:") + "/api/v1/worker-delivery/websocket"

    def _openssl(self, *args: str, input: bytes | None = None) -> bytes:
        completed = subprocess.run([self.openssl, *args], input=input, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=30, check=False)
        if completed.returncode:
            raise RuntimeError("OpenSSL fixture operation failed: " + completed.stderr.decode(errors="replace"))
        return completed.stdout

    def _certificates(self) -> None:
        self._openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                      "-subj", "/CN=XA Mass ingress proof CA", "-keyout", str(self.directory / "ca.key"),
                      "-out", str(self.ca_file), "-addext", "basicConstraints=critical,CA:TRUE")
        self._openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                      "-keyout", str(self.directory / "server.key"), "-out", str(self.directory / "server.csr"))
        extensions = self.directory / "server.ext"
        extensions.write_text("subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n", encoding="ascii")
        self._openssl("x509", "-req", "-in", str(self.directory / "server.csr"), "-CA", str(self.ca_file),
                      "-CAkey", str(self.directory / "ca.key"), "-CAcreateserial", "-days", "2",
                      "-extfile", str(extensions), "-out", str(self.certificate))
        for path in (self.directory / "ca.key", self.directory / "server.key"):
            path.chmod(0o600)

    def write_passwords(self, path: Path, credentials: tuple[tuple[str, str], ...]) -> None:
        lines = []
        for username, password in credentials:
            # Fixture passwords are sent over stdin, never in process arguments.
            encoded = self._openssl("passwd", "-apr1", "-stdin", input=(password + "\n").encode()).decode().strip()
            lines.append(username + ":" + encoded)
        path.write_text("\n".join(lines) + "\n", encoding="ascii")
        path.chmod(0o600)

    def start(self) -> Gateway:
        if self.process is not None:
            raise RuntimeError("Gateway is already running")
        command = [self.nginx, "-p", str(self.directory) + "/", "-c", str(self.configuration)]
        checked = subprocess.run(command + ["-t"], capture_output=True, timeout=15)
        if checked.returncode:
            raise RuntimeError("nginx -t failed: " + checked.stderr.decode(errors="replace"))
        self._log = (self.directory / "process.log").open("ab")
        self.process = subprocess.Popen(command + ["-g", "daemon off; master_process off;"],
                                        stdout=self._log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Nginx exited; inspect private fixture process.log")
            try:
                with socket.create_connection(("127.0.0.1", self.config.https_port), timeout=0.25):
                    return self
            except OSError:
                time.sleep(0.05)
        raise RuntimeError("Nginx fixture did not listen")

    def stop(self) -> None:
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
            self.process = None
        if self._log is not None:
            self._log.close()
            self._log = None

    def restart(self) -> None:
        self.stop()
        self.start()

    def __enter__(self) -> Gateway:
        try:
            return self.start()
        except BaseException:
            self.stop()
            raise

    def __exit__(self, *_):
        self.stop()

    def request(self, host: str, method: str, path: str, credential=None, *, headers=None, body=None):
        connection = http.client.HTTPSConnection(host, self.config.https_port, context=self.context, timeout=10)
        supplied = dict(headers or {})
        if credential is not None:
            supplied["Authorization"] = basic(credential)
        try:
            connection.request(method, path, body=body, headers=supplied)
            response = connection.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally:
            connection.close()

    def websocket(self, credential=None, *, path="/api/v1/worker-delivery/websocket"):
        raw = socket.create_connection(("127.0.0.1", self.config.https_port), timeout=10)
        stream = self.context.wrap_socket(raw, server_hostname="127.0.0.1")
        headers = [f"GET {path} HTTP/1.1", f"Host: 127.0.0.1:{self.config.https_port}",
                   "Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Version: 13",
                   "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="]
        if credential is not None:
            headers.append("Authorization: " + basic(credential))
        stream.sendall(("\r\n".join(headers) + "\r\n\r\n").encode("ascii"))
        received = b""
        while b"\r\n\r\n" not in received:
            chunk = stream.recv(1)
            if not chunk:
                raise RuntimeError("Gateway closed before its response headers")
            received += chunk
        status = int(received.split(b" ", 2)[1])
        if status != 101:
            stream.close()
            return status, None
        return status, stream


class _Backend(AbstractContextManager):
    def __init__(self):
        self.observations = []
        self.status = 200
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def handle_one(self):
                length = int(self.headers.get("Content-Length", 0))
                if length:
                    self.rfile.read(length)
                owner.observations.append({"method": self.command, "path": self.path,
                    "authorization": self.headers.get("Authorization"),
                    "proxyAuthorization": self.headers.get("Proxy-Authorization"),
                    "ingressUser": self.headers.get("X-XA-Ingress-User")})
                if self.headers.get("Upgrade", "").lower() == "websocket":
                    key = self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                    self.send_response(101)
                    self.send_header("Upgrade", "websocket")
                    self.send_header("Connection", "Upgrade")
                    self.send_header("Sec-WebSocket-Accept", base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
                    self.end_headers()
                    self.wfile.flush()
                    self.close_connection = True
                    try:
                        while True:
                            first, second = _read_exact(self.connection, 2)
                            if first & 0x0f == 8:
                                break
                            if first != 0x81 or not second & 0x80 or second & 0x7f >= 126:
                                raise ValueError("unexpected proof WebSocket frame")
                            mask = _read_exact(self.connection, 4)
                            encoded = _read_exact(self.connection, second & 0x7f)
                            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(encoded))
                            self.connection.sendall(bytes((0x81, len(payload))) + payload)
                    except (OSError, EOFError, ValueError):
                        pass
                    return
                payload = b'{"mockUpstream":true}'
                self.send_response(owner.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "https://untrusted.invalid")
                self.send_header("Access-Control-Allow-Credentials", "true")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_HEAD = do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = handle_one

        class Server(ThreadingHTTPServer):
            daemon_threads = True
            request_queue_size = 512

        self.server = Server(("127.0.0.1", 0), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)


def _verify_private(directory: Path, *, nginx="nginx", openssl="openssl") -> dict:
    """Run all routing, TLS, Basic separation, rate and actual 100/200 WSS checks."""
    checks = []

    def require(condition, name):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    with _Backend() as runtime, _Backend() as adapter, Gateway(directory, runtime.port, adapter.port,
                                                            nginx=nginx, openssl=openssl) as gate:
        def status(host, method, path, credential=None, **kwargs):
            return gate.request(host, method, path, credential, **kwargs)[0]

        prepare = "/api/v1/worker-groups/staging-workers/workers:prepare"
        require(status("localhost", "GET", "/", ADMIN) == 200, "admin TLS and Basic")
        require(status("localhost", "GET", "/static/js/index-test.js", ADMIN) == 200, "Vite JavaScript route")
        require(status("localhost", "GET", "/static/css/index-test.css", ADMIN) == 200, "Vite stylesheet route")
        require(status("localhost", "POST", "/static/js/index-test.js", ADMIN) == 404, "static files are read-only")
        require(status("localhost", "GET", "/") == 401, "anonymous admin denied")
        require(status("localhost", "GET", "/", WORKER) == 401, "Worker key cannot administer")
        require(status("127.0.0.1", "POST", prepare, ADMIN) == 401, "admin key cannot Prepare")
        require(status("127.0.0.1", "POST", prepare, WORKER) == 200, "Worker Prepare")
        require(status("127.0.0.1", "POST", prepare + "-batch", WORKER) == 200, "Worker batch Prepare")
        require(gate.websocket()[0] == 401, "anonymous WSS denied")
        require(gate.websocket(ADMIN)[0] == 401, "admin key cannot open WSS")
        _, _, response_headers = gate.request("localhost", "GET", "/", ADMIN)
        require(not any(name.lower().startswith("access-control-") for name in response_headers), "upstream CORS headers suppressed")
        require(status("localhost", "POST", "/api/v1/tasks", ADMIN,
                       headers={"Origin": gate.admin_url, "Sec-Fetch-Site": "same-origin"}) == 200, "same-origin administration")
        require(status("localhost", "POST", "/api/v1/tasks", ADMIN,
                       headers={"Origin": "https://untrusted.invalid"}) == 403, "foreign Origin mutation denied")
        require(status("localhost", "POST", "/api/v1/tasks", ADMIN,
                       headers={"Sec-Fetch-Site": "cross-site"}) == 403, "cross-site mutation denied")
        require(status("localhost", "POST", "/api/v1/worker-delivery/endpoint-managers/runtime-websocket/direct-calls", ADMIN) == 200,
                "Direct Call remains administrative")
        forbidden = [
            "/api/v1/worker-delivery/endpoint-managers/runtime-websocket/commands:consume",
            "/api/v1/worker-delivery/endpoint-managers/runtime-websocket/results:append",
            "/api/v1/worker-delivery/endpoint-managers/runtime-websocket/workers/worker-1/commands:poll",
            "/api/v1/worker-delivery/endpoint-managers/runtime-websocket/workers/worker-1/results",
        ]
        before = len(runtime.observations)
        for host, key in (("localhost", ADMIN), ("127.0.0.1", WORKER)):
            for path in forbidden:
                require(status(host, "POST", path, key) == 404, f"internal route denied {host} {path.rsplit('/', 1)[-1]}")
        require(len(runtime.observations) == before, "internal denials never reach Runtime")
        require(status("localhost", "POST", prepare, ADMIN) == 404, "Prepare absent from admin host")
        require(status("localhost", "GET", "/api/v1/worker-delivery/websocket", ADMIN) == 404, "WSS absent from admin host")
        require(status("127.0.0.1", "POST", prepare.replace("staging-workers", "other-group"), WORKER) == 404,
                "undeclared Group denied")
        require(status("127.0.0.1", "POST", "/api/v1/tasks", WORKER) == 404, "Worker cannot create Tasks")
        require(status("127.0.0.1", "GET", "/@authenticated_websocket", WORKER) == 404, "named location is private")
        require(status("localhost", "DELETE", "/api/v1/users/1000", ADMIN) == 404, "unlisted methods denied")
        require(status("localhost", "OPTIONS", "/api/v1/tasks", ADMIN) == 404, "no CORS preflight")
        for path in ("/%61pi/v1/tasks", "/api//v1/tasks", "/api/v1/tasks;extra", "/x/../api/v1/tasks"):
            require(status("localhost", "POST", path, ADMIN) == 400, "ambiguous path denied " + path)
        require(status("localhost", "GET", "/", ADMIN, headers={"Host": "unknown.invalid"}) == 404, "unknown host denied")
        require(status("localhost", "POST", "/api/v1/tasks", ADMIN,
                       headers={"Content-Length": str(16 * 1024 * 1024 + 1)}) == 413, "16 MiB body ceiling")
        require(status("localhost", "GET", "/", ADMIN,
                       headers={"Proxy-Authorization": "secret", "X-XA-Ingress-User": "forged"}) == 200,
                "header filtering request")
        require(all(not row["authorization"] and not row["proxyAuthorization"] and not row["ingressUser"]
                    for row in runtime.observations), "credentials and forged gateway identity stripped")
        runtime.status = 503
        before = len(runtime.observations)
        require(status("localhost", "POST", "/api/v1/tasks", ADMIN) == 503, "upstream failure returned")
        require(len(runtime.observations) == before + 1, "no mutation retry")
        runtime.status = 200
        plain = http.client.HTTPConnection("127.0.0.1", gate.config.http_port, timeout=5)
        try:
            plain.request("GET", "/", headers={"Host": "localhost"})
            require(plain.getresponse().status == 404, "HTTP does not serve or redirect application")
        finally:
            plain.close()
        challenge = Path(gate.config.acme_root) / ".well-known/acme-challenge/test-token"
        challenge.write_text("test-proof", encoding="ascii")
        plain = http.client.HTTPConnection("127.0.0.1", gate.config.http_port, timeout=5)
        plain.request("GET", "/.well-known/acme-challenge/test-token", headers={"Host": "localhost"})
        answer = plain.getresponse()
        require(answer.status == 200 and answer.read() == b"test-proof", "HTTP only serves ACME token")
        plain.close()
        plain = http.client.HTTPConnection("127.0.0.1", gate.config.http_port, timeout=5)
        plain.request("GET", "/.well-known/acme-challenge/test-token", headers={"Host": "unknown.invalid"})
        require(plain.getresponse().status == 404, "unknown HTTP host cannot read ACME")
        plain.close()
        untrusted = http.client.HTTPSConnection("localhost", gate.config.https_port, timeout=5)
        try:
            untrusted.request("GET", "/")
            untrusted.getresponse()
            raise AssertionError("untrusted test CA was accepted")
        except ssl.SSLCertVerificationError:
            checks.append("TLS certificate verification is active")
        finally:
            untrusted.close()
        raw = socket.create_connection(("127.0.0.1", gate.config.https_port), timeout=5)
        try:
            gate.context.wrap_socket(raw, server_hostname="wrong.invalid")
            raise AssertionError("certificate hostname mismatch was accepted")
        except ssl.SSLCertVerificationError:
            checks.append("TLS hostname verification is active")
        finally:
            raw.close()
        for host, key, method, path, count in (("localhost", ADMIN, "GET", "/", 130),
                                              ("127.0.0.1", WORKER, "POST", prepare, 70)):
            with ThreadPoolExecutor(max_workers=12) as executor:
                codes = list(executor.map(lambda _: status(host, method, path, key), range(count)))
            require(429 in codes, f"bounded HTTP rate {host}")
        time.sleep(4)
        opened = []
        try:
            for key in (WORKER, OTHER_WORKER):
                for _ in range(100):
                    code, stream = gate.websocket(key)
                    require(code == 101, "WSS admitted")
                    opened.append(stream)
                    time.sleep(0.11)
                if key == WORKER:
                    require(gate.websocket((WORKER[0], "wrong-password"))[0] == 401,
                            "Basic verification precedes credential connection limit")
                    require(gate.websocket(WORKER)[0] == 429, "100 WSS per verified credential")
            require(gate.websocket(THIRD_WORKER)[0] == 429, "200 WSS total")
            require(all(not row["authorization"] for row in adapter.observations), "WSS credentials stripped")
        finally:
            for stream in opened:
                stream.close()
        time.sleep(0.3)
        code, retained = gate.websocket(WORKER)
        require(code == 101, "closed WSS releases capacity")
        try:
            # Neither side sends heartbeats. Keep this exact raw socket; no client
            # reconnect implementation exists in this witness to hide a 60s timeout.
            connection_count = len(adapter.observations)
            quiet_started = time.monotonic()
            deadline = quiet_started + 65
            while time.monotonic() < deadline:
                time.sleep(min(5, deadline - time.monotonic()))
            idle_seconds = time.monotonic() - quiet_started
            payload = b"retained-socket-after-idle"
            mask = b"\x01\x02\x03\x04"
            encoded = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            retained.settimeout(5)
            retained.sendall(bytes((0x81, 0x80 | len(payload))) + mask + encoded)
            require(_read_exact(retained, 2 + len(payload)) == bytes((0x81, len(payload))) + payload,
                    "original raw WSS echoes after 65 seconds without heartbeat")
            require(idle_seconds >= 65 and len(adapter.observations) == connection_count,
                    "quiet WSS witness never reconnects")
            gate.write_passwords(gate.worker_password_file, (OTHER_WORKER,))
            require(gate.websocket(WORKER)[0] == 401, "revocation rejects new handshake")
            require(not select.select([retained], [], [], 0.1)[0], "revocation alone leaves established WSS")
            gate.restart()
            retained.settimeout(5)
            require(retained.recv(1) == b"", "restart closes established WSS")
            require(gate.websocket(WORKER)[0] == 401, "revoked credential remains rejected after restart")
            code, available = gate.websocket(OTHER_WORKER)
            require(code == 101, "remaining credential works after restart")
            available.close()
        finally:
            retained.close()
        logs = (Path(gate.config.state_directory) / "access.log").read_text(encoding="utf-8")
        require(all(password not in logs and basic((user, password)) not in logs
                    for user, password in (ADMIN, WORKER, OTHER_WORKER, THIRD_WORKER)), "access log omits credentials")
        require(all(" " + key + " " in logs for key in (ADMIN[0], WORKER[0], OTHER_WORKER[0])),
                "access log records nonsecret credential usernames")
    summary = {"status": "passed", "checks": sorted(set(checks)), "wssPerCredential": 100, "wssTotal": 200,
               "rawWssQuietSeconds": round(idle_seconds, 3),
               "claims": "TLS gateway routing and bounded admission with mock upstreams; not Worker execution"}
    return summary


def verify(directory: Path, *, nginx="nginx", openssl="openssl") -> dict:
    """Retain only the safe summary; close processes and remove private CA/logs."""
    directory = directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="xa-mass-ingress-proof-") as private:
        summary = _verify_private(Path(private), nginx=nginx, openssl=openssl)
    summary["privateFixtureCleaned"] = True
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", default="nginx")
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("the real Nginx proof runs on Linux")
    print(json.dumps(verify(args.output.resolve(), nginx=args.nginx, openssl=args.openssl)))


if __name__ == "__main__":
    main()
