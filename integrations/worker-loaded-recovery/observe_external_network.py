#!/usr/bin/env python3
"""Optional Linux CI observer around the unchanged published external consumer."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit

HOST_MAIN = "com.xa.mass.workersimulator.WorkerSimulatorMain"
COUNTERS = {"TcpActiveOpens", "TcpAttemptFails", "TcpEstabResets", "TcpRetransSegs",
            "TcpExtTCPTimeouts", "TcpExtTCPSynRetrans", "TcpExtListenOverflows", "TcpExtListenDrops"}
EVENTS = ("jdk.JavaExceptionThrow", "jdk.SocketRead", "jdk.SocketWrite", "jdk.TLSHandshake", "jdk.DataLoss")
MAX_PACKETS = 200_000
MAX_SYN_SENT = 100


def now():
    return datetime.now(timezone.utc).isoformat()


def append(path, value):
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, separators=(",", ":")) + "\n")


def origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment or parsed.path not in ("", "/")
            or not 1 <= (parsed.port or 443) <= 65535):
        raise ValueError("target must be an HTTPS origin")
    return parsed.hostname, parsed.port or 443


def options(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--consumer-output-dir", required=True, type=Path)
    parser.add_argument("--target-url")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    value = parser.parse_args(arguments)
    if value.command[:1] != ["--"] or len(value.command) < 2:
        parser.error("supply -- followed by the unchanged Release command")
    value.command = value.command[1:]
    target = value.target_url
    if target is None:
        for index, arg in enumerate(value.command):
            if arg == "--worker-runtime-url" and index + 1 < len(value.command): target = value.command[index + 1]
            elif arg.startswith("--worker-runtime-url="): target = arg.split("=", 1)[1]
    value.target_host, value.target_port = origin(target or "")
    value.output_dir, value.consumer_output_dir = value.output_dir.resolve(), value.consumer_output_dir.resolve()
    if (value.output_dir == value.consumer_output_dir or value.output_dir in value.consumer_output_dir.parents
            or value.consumer_output_dir in value.output_dir.parents):
        raise ValueError("diagnostic and consumer output directories must be separate")
    if value.consumer_output_dir.exists(): raise ValueError("consumer output must be new")
    value.output_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    if any(value.output_dir.iterdir()): raise ValueError("diagnostic output must be empty")
    return value


def process_identity(pid, proc=Path("/proc")):
    fields = (proc / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
    return int(fields[1]), int(fields[19])  # ppid and starttime (field 22).


def verify_host(pid, consumer_pid, expected_start=None, proc=Path("/proc")):
    if type(pid) is not int or pid <= 1: raise ValueError("invalid Host PID")
    parent, started = process_identity(pid, proc)
    command = (proc / str(pid) / "cmdline").read_bytes().split(b"\0")
    if HOST_MAIN.encode() not in command or expected_start is not None and started != expected_start:
        raise ValueError("Host PID identity mismatch")
    for _ in range(8):
        if parent == consumer_pid: break
        if parent <= 1: raise ValueError("Host is not owned by this consumer")
        parent, _ = process_identity(parent, proc)
    else: raise ValueError("Host ancestry is not confirmed")
    if process_identity(pid, proc)[1] != started: raise ValueError("Host PID changed during validation")
    return started


def endpoint(value, packet=False):
    address, port = value.rsplit("." if packet else ":", 1)
    return {"address": str(ipaddress.ip_address(address.strip("[]"))), "port": int(port)}


def packet_metadata(line):
    match = re.search(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*? IP6? (\S+) > (\S+): Flags \[([FSRPAUEW.]+)\]", line)
    if not match: return None
    value = {"at": match[1] + "Z", "source": endpoint(match[2], True), "destination": endpoint(match[3], True), "flags": match[4]}
    for field in ("seq", "ack"):
        if number := re.search(r"\b" + field + r" (\d+)(?=,|\s|$)", line): value[field] = int(number[1])
    return value


def capture_statistics(text):
    result = {}
    for phrase, name in (("captured", "captured"), ("received by filter", "received"), ("dropped by kernel", "dropped")):
        match = re.search(r"(?m)^(\d+) packets? " + phrase + r"\s*$", text)
        if match: result[name] = int(match[1])
    return result


def syn_sent_metadata(text, pid):
    rows = []
    for line in text.splitlines():
        if not re.search(r"\bpid=" + str(pid) + r"[,)]", line): continue
        addresses = []
        for part in line.split():
            try: addresses.append(endpoint(part))
            except (ValueError, IndexError): continue
        if len(addresses) >= 2: rows.append({"source": addresses[0], "destination": addresses[1]})
    return rows


def jfr_metadata(event):
    kind, value = event.get("type"), event.get("values", {})
    if kind not in EVENTS: return None
    result = {"event": kind, "at": value.get("startTime"), "duration": value.get("duration")}
    thread = value.get("eventThread") or {}
    if type(thread.get("javaThreadId")) is int: result["javaThreadId"] = thread["javaThreadId"]
    if kind == "jdk.DataLoss":
        for field in ("amount", "total"):
            if type(value.get(field)) is int: result[field] = value[field]
    elif kind == "jdk.JavaExceptionThrow":
        name = (value.get("thrownClass") or {}).get("name", "").replace("/", ".")
        if not (name.startswith("java.net.") or name.startswith("javax.net.ssl.") or name == "java.io.EOFException"
                or name == "com.xa.mass.worker.error.WorkerException"): return None
        frames = []
        for frame in (value.get("stackTrace") or {}).get("frames", [])[:64]:
            method = frame["method"]
            owner = method["type"]["name"].replace("/", ".")
            if owner.startswith("okhttp3.") and method["name"] == "isHealthy": return None
            frames.append({"class": owner, "method": method["name"], "line": frame.get("lineNumber")})
        result.update(exceptionClass=name, stack=frames)
        message = value.get("message", "")
        result["recognizedTimeout"] = {"Connect timed out": "connect-timeout", "connect timed out": "connect-timeout",
                                       "Read timed out": "read-timeout", "timeout": "timeout"}.get(message)
    elif kind == "jdk.TLSHandshake":
        result.update(peerHost=value.get("peerHost"), peerPort=value.get("peerPort"),
                      protocol=value.get("protocolVersion"), cipherSuite=value.get("cipherSuite"))
    else:
        result.update(address=str(ipaddress.ip_address(value["address"])), port=value["port"])
        for field in ("timeout", "bytesRead", "bytesWritten", "endOfStream"):
            if field in value: result[field] = value[field]
    return result


def stop_capture(process, wait_seconds=5):
    """The capture owns a new process group; sudo may retain a privileged monitor."""
    if process is None or process.poll() is not None: return
    for name in ("INT", "TERM", "KILL"):
        try:
            subprocess.run(["sudo", "-n", "kill", "-" + name, "--", "-" + str(process.pid)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=True)
        except subprocess.CalledProcessError:
            if process.poll() is not None: return
            raise
        try: process.wait(timeout=wait_seconds); return
        except subprocess.TimeoutExpired: pass
    raise RuntimeError("capture did not stop")


def consumer_exit_code(consumer_code, failures):
    if consumer_code < 0: return 128 - consumer_code
    return consumer_code if consumer_code != 0 else int(bool(failures))


class Observer:
    def __init__(self, value, private):
        self.value, self.private = value, private
        self.failures, self.packet_count, self.sample_count = [], 0, 0
        self.host, self.started, self.attached, self.jfr_stopped = None, None, False, False
        self.capture, self.reader = None, None
        self.reader_stop = threading.Event()
        self.capture_error_stream, self.capture_stats = None, {}
        self.offset = 0

    def failed(self, reason):
        if reason not in self.failures: self.failures.append(reason)

    def command(self, arguments, timeout=10):
        return subprocess.run(arguments, capture_output=True, text=True, timeout=timeout, check=True).stdout

    def start(self):
        addresses = sorted({str(ipaddress.ip_address(item[4][0])) for item in
                            socket.getaddrinfo(self.value.target_host, self.value.target_port, type=socket.SOCK_STREAM)})
        self.addresses = addresses
        # IPv4 flag offsets and IPv6's ordinary TCP header; never capture/print payload bytes.
        targets = " or ".join("host " + address for address in addresses)
        expression = f"({targets}) and tcp port {self.value.target_port} and ((ip and tcp[13] & 6 != 0) or (ip6 and ip6[53] & 6 != 0))"
        self.capture_error_stream = (self.private / "tcpdump.stderr").open("wb")
        self.capture = subprocess.Popen(["sudo", "-n", "tcpdump", "-i", "any", "-nn", "-l", "-tttt", "-S", expression],
                                        stdout=subprocess.PIPE, stderr=self.capture_error_stream, bufsize=0, start_new_session=True,
                                        env={**os.environ, "TZ": "UTC", "LC_ALL": "C"})
        self.reader = threading.Thread(target=self.read_capture, name="external-network-capture", daemon=True)
        self.reader.start()

    def read_capture(self):
        # Do not block in buffered readline: a failed stop can leave the writer open.
        stream = self.capture.stdout
        pending = b""
        try:
            descriptor = stream.fileno()
            os.set_blocking(descriptor, False)
            while not self.reader_stop.is_set():
                if not select.select([descriptor], [], [], 0.1)[0]: continue
                try: chunk = os.read(descriptor, 65536)
                except BlockingIOError: continue
                if not chunk:
                    if pending: self.failed("tcp-capture-incomplete-line")
                    return
                lines = (pending + chunk).split(b"\n")
                pending = lines.pop()
                for line in lines:
                    if self.reader_stop.is_set(): return
                    value = packet_metadata(line.decode("utf-8"))
                    if value:
                        if self.packet_count >= MAX_PACKETS:
                            self.failed("tcp-packet-metadata-truncated")
                            continue  # Keep draining the pipe so the owned capture can stop normally.
                        append(self.value.output_dir / "tcp-handshakes.jsonl", value)
                        self.packet_count += 1
        except Exception: self.failed("tcp-capture-read-failed")
        finally:
            stream.close()

    def attach(self, consumer_pid):
        path = self.value.consumer_output_dir / "process-resources.jsonl"
        if not path.exists(): return
        with path.open(encoding="utf-8") as stream:
            stream.seek(self.offset)
            while line := stream.readline():
                if not line.endswith("\n"): break
                self.offset = stream.tell()
                row = json.loads(line)
                if row.get("process") != "worker-host": continue
                self.host = row.get("pid")
                self.started = verify_host(self.host, consumer_pid)
                config = self.private / "network.jfc"
                events = []
                for name in EVENTS:
                    settings = '<setting name="enabled">true</setting><setting name="stackTrace">' + str(name == EVENTS[0]).lower() + '</setting>'
                    if name in EVENTS[1:3]: settings += '<setting name="threshold">100 ms</setting>'
                    events.append(f'<event name="{name}">{settings}</event>')
                config.write_text('<configuration version="2.0" label="External network">' + ''.join(events) + '</configuration>', encoding="utf-8")
                verify_host(self.host, consumer_pid, self.started)
                self.command(["jcmd", str(self.host), "JFR.start", "name=external-network", "settings=" + str(config),
                              "maxage=3m", "maxsize=64m", "dumponexit=true", "filename=" + str(self.private / "network.jfr")])
                self.attached = True
                return

    def sample(self, consumer_pid):
        if self.capture is not None and self.capture.poll() is not None: self.failed("tcp-capture-exited")
        if self.host is None: self.attach(consumer_pid)
        row = {"at": now(), "kernelScope": "consumer-machine"}
        text = self.command(["nstat", "-asz", *sorted(COUNTERS)], 3)
        row["counters"] = {parts[0]: int(parts[1]) for line in text.splitlines()
                           if len(parts := line.split()) >= 2 and parts[0] in COUNTERS and parts[1].isdigit()}
        if not row["counters"]: raise ValueError("no kernel counters")
        if self.host is not None:
            try: verify_host(self.host, consumer_pid, self.started)
            except FileNotFoundError: row["hostExited"] = True
            else:
                row["hostPid"] = self.host
                connections = syn_sent_metadata(self.command(["ss", "-Hntp", "state", "syn-sent"], 3), self.host)
                row.update(synSent=connections[:MAX_SYN_SENT], synSentTotal=len(connections), synSentTruncated=len(connections) > MAX_SYN_SENT)
                if row["synSentTruncated"]: self.failed("syn-sent-metadata-truncated")
                timeline = self.value.consumer_output_dir / "evidence/timeline.jsonl"
                if self.attached and not self.jfr_stopped and timeline.exists():
                    if any(json.loads(line).get("phase") not in {"preflight", "inventory", "ramp"}
                           for line in timeline.read_text().splitlines() if line.endswith("}")):
                        self.command(["jcmd", str(self.host), "JFR.stop", "name=external-network", "filename=" + str(self.private / "network.jfr")])
                        self.jfr_stopped = True
        append(self.value.output_dir / "network-state.jsonl", row)
        self.sample_count += 1

    def close(self):
        try: stop_capture(self.capture)
        except Exception:
            self.failed("tcp-capture-cleanup-failed")
            self.reader_stop.set()
        if self.reader:
            # Normal shutdown drains to EOF; a retained writer must not prevent finalization.
            self.reader.join(5)
            if self.reader.is_alive():
                self.failed("tcp-reader-did-not-stop")
                self.reader_stop.set()
                self.reader.join(1)
        if self.capture and self.capture.stdout and (not self.reader or not self.reader.is_alive()):
            self.capture.stdout.close()
        if self.capture_error_stream: self.capture_error_stream.close()
        error_file = self.private / "tcpdump.stderr"
        if error_file.exists(): self.capture_stats = capture_statistics(error_file.read_text(errors="replace"))
        if set(self.capture_stats) != {"captured", "received", "dropped"}: self.failed("tcp-capture-statistics-missing")
        elif self.capture_stats["dropped"]: self.failed("tcp-capture-kernel-drops")
        elif self.capture_stats["captured"] != self.packet_count: self.failed("tcp-capture-parse-or-limit-gap")
        if not self.attached: self.failed("jfr-not-attached")
        recording = self.private / "network.jfr"
        try:
            data = json.loads(self.command(["jfr", "print", "--json", "--stack-depth", "64", "--events", ",".join(EVENTS), str(recording)], 60))
            for event in data["recording"]["events"]:
                value = jfr_metadata(event)
                if value:
                    append(self.value.output_dir / "jfr-metadata.jsonl", value)
                    if value["event"] == "jdk.DataLoss" and value.get("amount", 0) > 0:
                        self.failed("jfr-data-loss")
        except Exception: self.failed("jfr-export-incomplete")
        if self.sample_count == 0: self.failed("network-samples-missing")
        if self.packet_count == 0: self.failed("tcp-packets-missing")


def run(value):
    consumer, observer, code, cancelled = None, None, 1, False
    interrupted = threading.Event()
    previous = {sig: signal.signal(sig, lambda *_: interrupted.set()) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        temporary = tempfile.TemporaryDirectory(prefix="xa-external-network-")
        observer = Observer(value, Path(temporary.name))
        private_removed = False
        try:
            try: observer.start()
            except Exception: observer.failed("capture-start-failed")
            try:
                if interrupted.is_set():
                    cancelled, code = True, 130
                    raise InterruptedError("cancelled before consumer launch")
                # Isolate terminal group signals; only this wrapper forwards cancellation.
                consumer = subprocess.Popen(value.command, start_new_session=True)
                while consumer.poll() is None:
                    if interrupted.is_set():
                        cancelled = True
                        consumer.terminate()
                        try: consumer.wait(timeout=90)
                        except subprocess.TimeoutExpired:
                            observer.failed("consumer-cleanup-timeout"); consumer.kill(); consumer.wait(timeout=10)
                        break
                    try: observer.sample(consumer.pid)
                    except Exception: observer.failed("network-observation-incomplete")
                    interrupted.wait(1)
                code = consumer.returncode
            except Exception: observer.failed("consumer-launch-or-wait-failed")
            finally:
                if consumer and consumer.poll() is None:
                    consumer.terminate()
                    try: consumer.wait(timeout=90)
                    except subprocess.TimeoutExpired:
                        observer.failed("consumer-cleanup-timeout"); consumer.kill(); consumer.wait(timeout=10)
                    code = consumer.returncode
                try: observer.close()
                except Exception: observer.failed("diagnostic-finalization-failed")
        finally:
            try: temporary.cleanup(); private_removed = True
            except Exception: observer.failed("private-temporary-cleanup-failed")
        summary = {"status": "complete" if not observer.failures else "incomplete", "consumerExitCode": code,
                   "cancelled": cancelled, "failures": observer.failures, "packetCount": observer.packet_count,
                   "networkSampleCount": observer.sample_count, "jfrAttached": observer.attached,
                   "captureStatistics": observer.capture_stats,
                   "jfrScope": "last 3 minutes of bootstrap; stops before workload", "privateTemporaryFilesRemoved": private_removed,
                   "targetAddresses": getattr(observer, "addresses", []), "targetPort": value.target_port,
                   "nonClaims": ["packet capture PID attribution", "TCP connect destination from JFR alone", "IPv6 extension-header coverage"]}
        try: (value.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        except Exception: observer.failed("summary-write-failed")
        return consumer_exit_code(code, observer.failures)
    finally:
        for sig, handler in previous.items(): signal.signal(sig, handler)


def main(arguments=None):
    try:
        value = options(arguments)
        if sys.platform != "linux": raise ValueError("observer requires Linux")
        return run(value)
    except Exception:
        print("External network observer could not complete; no private diagnostic text is printed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
