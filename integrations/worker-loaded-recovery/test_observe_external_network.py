import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("external_network_observer", Path(__file__).with_name("observe_external_network.py"))
OBSERVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OBSERVER)


def exception_event(owner="java.net.SocketTimeoutException", method="timedFinishConnect", message="Connect timed out"):
    return {"type": "jdk.JavaExceptionThrow", "values": {
        "startTime": "2026-11-12T12:00:00Z", "duration": "PT0S", "message": message,
        "thrownClass": {"name": owner}, "eventThread": {"javaThreadId": 42, "javaName": "secret-thread-value"},
        "stackTrace": {"frames": [{"method": {"type": {"name": "sun.nio.ch.NioSocketImpl"}, "name": method}, "lineNumber": 1}]}}}


class MetadataTest(unittest.TestCase):
    def test_exception_export_keeps_only_known_metadata_and_never_arbitrary_messages(self):
        event = exception_event(message="credential=VERY_PRIVATE; body=PAYLOAD")
        event["values"]["properties"] = {"secret": "VERY_PRIVATE"}
        result = OBSERVER.jfr_metadata(event)
        self.assertEqual("java.net.SocketTimeoutException", result["exceptionClass"])
        self.assertEqual(42, result["javaThreadId"])
        self.assertIsNone(result["recognizedTimeout"])
        self.assertNotIn("VERY_PRIVATE", json.dumps(result))
        self.assertNotIn("PAYLOAD", json.dumps(result))
        self.assertNotIn("secret-thread", json.dumps(result))
        self.assertEqual("connect-timeout", OBSERVER.jfr_metadata(exception_event())["recognizedTimeout"])

    def test_expected_one_millisecond_okhttp_probe_and_unrelated_exceptions_are_excluded(self):
        event = exception_event()
        event["values"]["stackTrace"]["frames"][0]["method"] = {
            "type": {"name": "okhttp3.internal._UtilJvmKt"}, "name": "isHealthy"}
        self.assertIsNone(OBSERVER.jfr_metadata(event))
        self.assertIsNone(OBSERVER.jfr_metadata(exception_event("java.lang.IllegalArgumentException")))
        self.assertIsNotNone(OBSERVER.jfr_metadata(exception_event("javax.net.ssl.SSLHandshakeException")))

    def test_socket_and_packet_exports_never_contain_payload_or_command_text(self):
        event = {"type": "jdk.SocketRead", "values": {"startTime": "2026-11-12T12:00:00Z", "address": "192.0.2.1",
                 "port": 443, "timeout": 10000, "bytesRead": 0, "payload": "PRIVATE"}}
        self.assertNotIn("PRIVATE", json.dumps(OBSERVER.jfr_metadata(event)))
        packet = OBSERVER.packet_metadata("2026-11-12 12:00:00.123456 eth0 Out IP 192.0.2.2.34567 > 192.0.2.1.443: Flags [SEW], seq 2, length 0 PRIVATE")
        self.assertEqual({"address": "192.0.2.1", "port": 443}, packet["destination"])
        self.assertEqual(2, packet["seq"])
        self.assertNotIn("PRIVATE", json.dumps(packet))
        self.assertEqual("2001:db8::1", OBSERVER.packet_metadata(
            "2026-11-12 12:00:00.123456 IP6 2001:db8::2.34567 > 2001:db8::1.443: Flags [S.], seq 1")["destination"]["address"])
        self.assertIsNone(OBSERVER.packet_metadata("tcpdump: arbitrary error SECRET"))
        self.assertEqual({"captured": 10, "received": 11, "dropped": 1}, OBSERVER.capture_statistics(
            "PRIVATE ERROR TEXT\n10 packets captured\n11 packets received by filter\n1 packet dropped by kernel\n"))

    def test_syn_sent_is_restricted_to_owned_pid_and_keeps_the_actual_port(self):
        text = ('0 1 192.0.2.2:45678 192.0.2.1:1080 users:(("java",pid=123,fd=9))\n'
                '0 1 192.0.2.2:45679 192.0.2.9:443 users:(("other-secret",pid=1234,fd=10))')
        self.assertEqual([{"source": {"address": "192.0.2.2", "port": 45678},
                           "destination": {"address": "192.0.2.1", "port": 1080}}], OBSERVER.syn_sent_metadata(text, 123))


class IdentityTest(unittest.TestCase):
    def process(self, root, pid, parent, started, main=OBSERVER.HOST_MAIN):
        path = root / str(pid)
        path.mkdir(exist_ok=True)
        fields = ["S", str(parent), *(["0"] * 17), str(started)]
        (path / "stat").write_text(str(pid) + " (java worker) " + " ".join(fields))
        (path / "cmdline").write_bytes(b"java\0" + main.encode() + b"\0private-config-path\0")

    def test_requires_main_ancestry_and_unchanged_starttime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.process(root, 123, 10, 500)
            self.process(root, 10, 1, 400, "python")
            self.assertEqual(500, OBSERVER.verify_host(123, 10, proc=root))
            with self.assertRaises(ValueError): OBSERVER.verify_host(123, 10, 499, proc=root)
            with self.assertRaises(ValueError): OBSERVER.verify_host(123, 11, proc=root)
            self.process(root, 123, 10, 500, "unrelated.Main")
            with self.assertRaises(ValueError): OBSERVER.verify_host(123, 10, proc=root)

    def test_attach_rejects_mismatched_pid_before_jcmd(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "process-resources.jsonl").write_text('{"process":"worker-host","pid":123}\n')
            value = argparse.Namespace(consumer_output_dir=root, output_dir=root)
            observer = OBSERVER.Observer(value, root)
            with patch.object(OBSERVER, "verify_host", side_effect=ValueError("PID mismatch")), patch.object(observer, "command") as command:
                with self.assertRaises(ValueError): observer.attach(100)
                command.assert_not_called()


class LifecycleTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "linux", "capture pipe polling requires Linux")
    def test_open_writer_cannot_block_summary_or_private_cleanup(self):
        wrapper_source = textwrap.dedent('''
            import argparse, importlib.util, json, os, sys, time
            from pathlib import Path
            from unittest.mock import Mock, patch
            spec = importlib.util.spec_from_file_location("observer", sys.argv[1])
            observer = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(observer)
            root, stop_fails, consumer_code = Path(sys.argv[2]), sys.argv[3] == "fail", int(sys.argv[4])
            instances = []
            class PipeObserver(observer.Observer):
                def start(self):
                    instances.append(self)
                    read_fd, self.writer = os.pipe()
                    def capture(*args, **kwargs):
                        # Respect the real Popen stream mode, including the former buffered reader.
                        self.stream = os.fdopen(read_fd, "r" if kwargs.get("text") else "rb")
                        return Mock(stdout=self.stream, poll=Mock(return_value=None))
                    with patch.object(observer.socket, "getaddrinfo", return_value=[(None, None, None, None, ("192.0.2.1", 443))]), \
                         patch.object(observer.subprocess, "Popen", side_effect=capture):
                        super().start()
                    (self.private / "network.jfr").write_text("PRIVATE_RECORDING")
                    self.attached, self.sample_count = True, 1
                def sample(self, consumer_pid): pass
                def command(self, *args): return '{"recording":{"events":[]}}'
            value = argparse.Namespace(command=[sys.executable, "-c", "import sys; sys.exit(" + str(consumer_code) + ")"],
                                       output_dir=root, target_host="workers.example.test", target_port=443)
            started = time.monotonic()
            try:
                with patch.object(observer, "Observer", PipeObserver), \
                     patch.object(observer, "stop_capture", side_effect=OSError("PRIVATE_STOP_FAILURE") if stop_fails else None):
                    code = observer.run(value)
                instance = instances[0]
                summary = json.loads((root / "summary.json").read_text())
                assert code == (consumer_code or 1), (code, summary)
                assert summary["consumerExitCode"] == consumer_code
                assert summary["status"] == "incomplete"
                expected = "tcp-capture-cleanup-failed" if stop_fails else "tcp-reader-did-not-stop"
                assert expected in summary["failures"], summary
                assert not instance.reader.is_alive()
                assert instance.stream.closed
                assert summary["privateTemporaryFilesRemoved"] and not instance.private.exists()
                assert "PRIVATE" not in json.dumps(summary)
                assert time.monotonic() - started < 12
            finally:
                for instance in instances:
                    os.close(instance.writer)
        ''')
        # Failed stop must preserve the consumer failure; successful stop with a retained
        # writer must still fail diagnostics, even when the consumer itself succeeds.
        for stop, code in (("fail", 7), ("retained-writer", 0)):
            with self.subTest(stop=stop), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run([sys.executable, "-c", wrapper_source, str(Path(OBSERVER.__file__).resolve()),
                                         directory, stop, str(code)], capture_output=True, text=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)

    @unittest.skipUnless(sys.platform == "linux", "capture pipe polling requires Linux")
    def test_capture_drains_split_lines_at_eof_and_reports_incomplete_tail(self):
        packet = b"2026-11-12 12:00:00.123456 IP 192.0.2.2.34567 > 192.0.2.1.443: Flags [S], seq 2, length 0"
        for complete_tail in (True, False):
            with self.subTest(complete_tail=complete_tail), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                value = argparse.Namespace(output_dir=root, target_host="workers.example.test", target_port=443)
                observer = OBSERVER.Observer(value, root)
                read_fd, writer = os.pipe()
                stream = os.fdopen(read_fd, "rb", buffering=0)
                capture = Mock(stdout=stream, poll=Mock(return_value=0))
                try:
                    with patch.object(OBSERVER.socket, "getaddrinfo", return_value=[(None, None, None, None, ("192.0.2.1", 443))]), \
                         patch.object(OBSERVER.subprocess, "Popen", return_value=capture):
                        observer.start()
                    os.write(writer, packet + b"\n" + packet[:40])
                    deadline = time.monotonic() + 3
                    while observer.packet_count == 0 and time.monotonic() < deadline: time.sleep(0.01)
                    self.assertEqual(1, observer.packet_count)
                    os.write(writer, packet[40:] + (b"\n" if complete_tail else b""))
                    os.close(writer)
                    writer = None
                    (root / "tcpdump.stderr").write_text("2 packets captured\n2 packets received by filter\n0 packets dropped by kernel\n")
                    observer.attached, observer.sample_count = True, 1
                    with patch.object(observer, "command", return_value='{"recording":{"events":[]}}'):
                        observer.close()
                    self.assertFalse(observer.reader.is_alive())
                    self.assertTrue(stream.closed)
                    rows = [json.loads(line) for line in (root / "tcp-handshakes.jsonl").read_text().splitlines()]
                    self.assertEqual(2 if complete_tail else 1, len(rows))
                    self.assertEqual([] if complete_tail else ["tcp-capture-incomplete-line", "tcp-capture-parse-or-limit-gap"], observer.failures)
                finally:
                    if writer is not None: os.close(writer)
                    observer.reader_stop.set()
                    if observer.reader: observer.reader.join(2)
                    if observer.capture_error_stream: observer.capture_error_stream.close()
                    if not observer.reader or not observer.reader.is_alive(): stream.close()

    def test_privileged_capture_gets_sigint_then_bounded_escalation_for_only_its_group(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("tcpdump", 5), 0]
        with patch.object(OBSERVER.subprocess, "run") as call:
            OBSERVER.stop_capture(process)
        self.assertEqual(["sudo", "-n", "kill", "-INT", "--", "-123"], call.call_args_list[0].args[0])
        self.assertEqual(["sudo", "-n", "kill", "-TERM", "--", "-123"], call.call_args_list[1].args[0])

    def test_failed_consumer_code_is_preserved_when_diagnostics_fail(self):
        self.assertEqual(3, OBSERVER.consumer_exit_code(3, ["diagnostic-failed"]))
        self.assertEqual(42, OBSERVER.consumer_exit_code(42, ["diagnostic-failed"]))
        self.assertEqual(1, OBSERVER.consumer_exit_code(0, ["diagnostic-failed"]))
        self.assertEqual(0, OBSERVER.consumer_exit_code(0, []))

    def test_capture_exit_race_does_not_claim_cleanup_failed(self):
        process = Mock(pid=123)
        process.poll.side_effect = [None, 0]
        with patch.object(OBSERVER.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "kill")):
            OBSERVER.stop_capture(process)

    def test_kernel_drops_are_incomplete_without_exposing_stderr(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observer = OBSERVER.Observer(argparse.Namespace(output_dir=root), root)
            observer.attached, observer.sample_count, observer.packet_count = True, 1, 1
            (root / "tcpdump.stderr").write_text("PRIVATE\n1 packet captured\n2 packets received by filter\n1 packet dropped by kernel\n")
            with patch.object(observer, "command", return_value='{"recording":{"events":[]}}'): observer.close()
            self.assertIn("tcp-capture-kernel-drops", observer.failures)
            self.assertNotIn("PRIVATE", json.dumps(observer.capture_stats))

    def test_jfr_data_loss_is_incomplete_and_exports_only_numeric_loss_metadata(self):
        for amount in (0, 1024):
            with self.subTest(amount=amount), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                observer = OBSERVER.Observer(argparse.Namespace(output_dir=root), root)
                observer.attached, observer.sample_count, observer.packet_count = True, 1, 1
                (root / "tcpdump.stderr").write_text("1 packet captured\n1 packet received by filter\n0 packets dropped by kernel\n")
                event = {"type": "jdk.DataLoss", "values": {"startTime": "2026-11-12T12:00:00Z",
                         "duration": "PT0S", "amount": amount, "total": 4096,
                         "message": "PRIVATE_EXCEPTION_MESSAGE", "payload": "PRIVATE_BODY"}}
                with patch.object(observer, "command", return_value=json.dumps({"recording": {"events": [event]}})):
                    observer.close()
                self.assertEqual(["jfr-data-loss"] if amount else [], observer.failures)
                exported = json.loads((root / "jfr-metadata.jsonl").read_text())
                self.assertEqual({"event", "at", "duration", "amount", "total"}, set(exported))
                self.assertEqual(amount, exported["amount"])
                self.assertEqual(4096, exported["total"])
                self.assertNotIn("PRIVATE", json.dumps(exported))

    @unittest.skipUnless(sys.platform == "linux", "real POSIX process-group cancellation requires Linux")
    def test_group_sigint_is_forwarded_once_and_consumer_finally_finishes(self):
        consumer_source = textwrap.dedent('''
            import json, os, signal, sys, time
            from pathlib import Path
            root = Path(sys.argv[1])
            def interrupted(signum, frame):
                with (root / "signals").open("a") as output:
                    output.write(str(signum) + "\\n")
                raise InterruptedError("cancelled")
            signal.signal(signal.SIGINT, interrupted)
            signal.signal(signal.SIGTERM, interrupted)
            (root / "ready.json").write_text(json.dumps({"pid": os.getpid(), "group": os.getpgrp()}))
            try:
                time.sleep(30)
            except InterruptedError:
                pass
            finally:
                (root / "cleanup-started").write_text("yes")
                time.sleep(0.4)
                (root / "cleanup-complete").write_text("yes")
            sys.exit(23)
        ''')
        wrapper_source = textwrap.dedent('''
            import argparse, importlib.util, sys
            from pathlib import Path
            spec = importlib.util.spec_from_file_location("observer", sys.argv[1])
            observer = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(observer)
            root = Path(sys.argv[2])
            class FakeObserver:
                def __init__(self, value, private):
                    self.failures, self.capture_stats = [], {}
                    self.packet_count, self.sample_count, self.attached = 1, 1, True
                def start(self): pass
                def sample(self, consumer_pid): pass
                def failed(self, reason): self.failures.append(reason)
                def close(self): (root / "observer-closed").write_text("yes")
            observer.Observer = FakeObserver
            value = argparse.Namespace(command=[sys.executable, str(root / "consumer.py"), str(root)],
                                       output_dir=root / "diagnostics", target_port=443)
            sys.exit(observer.run(value))
        ''')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "consumer.py").write_text(consumer_source)
            (root / "diagnostics").mkdir()
            wrapper = subprocess.Popen([sys.executable, "-c", wrapper_source,
                                        str(Path(OBSERVER.__file__).resolve()), str(root)],
                                       start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 5
                while not (root / "ready.json").exists() and wrapper.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue((root / "ready.json").is_file(), "consumer did not install signal handlers")
                identity = json.loads((root / "ready.json").read_text())
                os.killpg(wrapper.pid, signal.SIGINT)
                stdout, stderr = wrapper.communicate(timeout=10)
                self.assertEqual(23, wrapper.returncode, stderr)
                self.assertNotEqual(wrapper.pid, identity["group"])
                self.assertEqual([str(int(signal.SIGTERM))], (root / "signals").read_text().splitlines())
                self.assertTrue((root / "cleanup-complete").is_file())
                self.assertTrue((root / "observer-closed").is_file())
                summary = json.loads((root / "diagnostics/summary.json").read_text())
                self.assertTrue(summary["cancelled"])
                self.assertEqual(23, summary["consumerExitCode"])
                self.assertTrue(summary["privateTemporaryFilesRemoved"])
            finally:
                if wrapper.poll() is None:
                    wrapper.kill()
                    wrapper.communicate(timeout=5)
                if (root / "ready.json").exists():
                    pid = json.loads((root / "ready.json").read_text())["pid"]
                    try:
                        if str(root / "consumer.py").encode() in Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0"):
                            os.kill(pid, signal.SIGKILL)
                    except (FileNotFoundError, ProcessLookupError):
                        pass

    def test_original_command_runs_and_private_material_is_removed_after_failure(self):
        instances = []
        class FailedObserver:
            def __init__(self, value, private):
                self.private = private
                self.failures, self.packet_count, self.sample_count, self.attached = ["capture-start-failed"], 0, 0, False
                self.capture_stats = {}
                self.closed = False
                instances.append(self)
            def start(self): (self.private / "raw.jfr").write_text("PRIVATE_EXCEPTION_MESSAGE")
            def failed(self, reason): self.failures.append(reason)
            def sample(self, consumer_pid): pass
            def close(self):
                self.closed = True
                raise OSError("private finalization detail must not replace consumer exit")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = [sys.executable, "-c", "import sys; sys.exit(7)"]
            value = argparse.Namespace(command=command, output_dir=root, target_port=443)
            with patch.object(OBSERVER, "Observer", FailedObserver): code = OBSERVER.run(value)
            summary = json.loads((root / "summary.json").read_text())
            self.assertEqual(7, code)
            self.assertEqual(7, summary["consumerExitCode"])
            self.assertEqual("incomplete", summary["status"])
            self.assertTrue(instances[0].closed)
            self.assertIn("diagnostic-finalization-failed", summary["failures"])
            self.assertFalse(instances[0].private.exists())
            self.assertNotIn("PRIVATE_EXCEPTION_MESSAGE", json.dumps(summary))

    def test_target_can_be_observed_from_unchanged_command_and_origins_are_strict(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = ["python", "release/acceptance/run_loaded.py", "--worker-runtime-url", "https://workers.example.test"]
            value = OBSERVER.options(["--output-dir", str(root / "diagnostics"), "--consumer-output-dir", str(root / "consumer"), "--", *command])
            self.assertEqual(command, value.command)
            self.assertEqual(("workers.example.test", 443), (value.target_host, value.target_port))
            for invalid in ("http://workers.example.test", "https://secret@workers.example.test", "https://workers.example.test/private"):
                with self.assertRaises(ValueError): OBSERVER.origin(invalid)


if __name__ == "__main__":
    unittest.main()
