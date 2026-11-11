from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision_worker_access as provision

REAL_RUN = subprocess.run


class ProvisionWorkerAccessTest(unittest.TestCase):
    def setUp(self):
        self.directory = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(self.directory)
        self.credential = self.root / "private.json"
        self.value = {"allowedOrigin": "https://workers.example", "keyId": "host-a",
                      "secret": "private-secret-秘密"}
        self.write(self.value)
        self.package = provision.PACKAGES[1]

    def write(self, value):
        self.credential.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def test_exact_utf8_fields_preserve_secret_and_support_secure_origins(self):
        for origin in ("https://workers.example", "HTTPS://WORKERS.example:443/", "https://127.0.0.1:8443", "https://[::1]:443/"):
            self.write(dict(self.value, allowedOrigin=origin, secret="  秘密  "))
            parsed = json.loads(provision.read_credential(self.credential))
            self.assertEqual("  秘密  ", parsed["secret"])
            self.assertEqual(origin, parsed["allowedOrigin"])
        # Java String.isBlank does not classify NBSP as blank.
        self.write(dict(self.value, secret="\u00a0"))
        self.assertEqual("\u00a0", json.loads(provision.read_credential(self.credential))["secret"])

    def test_invalid_origins_and_sdk_credential_shapes_are_rejected(self):
        origins = ["http://workers.example", "https://user:pass@workers.example", "https://workers.example/api",
                   "https://workers.example?", "https://workers.example#", "https://workers.example:0",
                   "https://workers.example:65536", "https://workers.example\\evil", "https://%65xample.test",
                   "https://例子.example", "https://", " https://workers.example", "https://999.999.999.999"]
        for origin in origins:
            with self.subTest(origin=origin):
                self.write(dict(self.value, allowedOrigin=origin))
                with self.assertRaises(provision.ProvisionError): provision.read_credential(self.credential)
        for field, bad in [("keyId", ""), ("keyId", " key"), ("keyId", "a:b"), ("keyId", "x" * 129),
                           ("secret", ""), ("secret", " \u2000"), ("secret", "secret\n"), ("secret", "\u0085"),
                           ("secret", "a" * 4097), ("secret", None), ("allowedOrigin", 3)]:
            with self.subTest(field=field, bad=bad):
                self.write(dict(self.value, **{field: bad}))
                with self.assertRaises(provision.ProvisionError): provision.read_credential(self.credential)

    def test_file_parser_is_bounded_strict_and_redacts_failures(self):
        bad_inputs = [b"", b"\xff", b"\xef\xbb\xbf{}", b"[]", b"{}", b"{" + b" " * provision.MAX_CREDENTIAL_BYTES,
                      b'{"allowedOrigin":"https://workers.example","keyId":"one","keyId":"two","secret":"private-secret"}',
                      json.dumps(dict(self.value, extra=True)).encode(),
                      json.dumps({"allowedOrigin": self.value["allowedOrigin"], "keyId": "host-a"}).encode()]
        for raw in bad_inputs:
            self.credential.write_bytes(raw)
            with self.assertRaises(provision.ProvisionError) as caught:
                provision.read_credential(self.credential)
            self.assertNotIn("private-secret", str(caught.exception))
            self.assertTrue(caught.exception.__suppress_context__)
        self.credential.unlink()
        with self.assertRaises(provision.ProvisionError): provision.read_credential(self.credential)

    def fake_adb(self, *, running=False, run_as=True, installed=True, available=True, publish_failure=False,
                 running_at_publish=False, truncated_input=False):
        app = self.root / "app"
        (app / "no_backup").mkdir(parents=True, exist_ok=True)
        destination = app / provision.DESTINATION
        destination.write_bytes(b"old-confirmed-file")
        calls = []
        bash = Path("C:/Program Files/Git/bin/bash.exe") if os.name == "nt" else None
        executable = str(bash) if bash and bash.is_file() else shutil.which("bash")
        self.assertIsNotNone(executable, "Provisioning shell contract requires Bash")

        def run(command, *, input=None, **kwargs):
            calls.append((command, input))
            if command == ["adb", "devices"]:
                return subprocess.CompletedProcess(command, 0, b"device-one\tdevice\n" if available else b"device-one\tunauthorized\n", b"")
            prefix = ["adb", "-s", "device-one"]
            self.assertEqual(prefix, command[:3])
            arguments = command[3:]
            if arguments[:3] == ["shell", "pm", "path"]:
                return subprocess.CompletedProcess(command, 0 if installed else 1, b"package:/data/app/base.apk\n" if installed else b"", b"")
            if arguments[:2] == ["shell", "run-as"]:
                return subprocess.CompletedProcess(command, 0 if run_as else 1, b"10123\n" if run_as else b"", b"private-secret diagnostic")
            if arguments[:2] == ["shell", "pidof"]:
                return subprocess.CompletedProcess(command, 0 if running else 1, b"42\n" if running else b"", b"")
            self.assertEqual(["shell", "-T", "run-as", self.package, "sh", "-c"], arguments[:6])
            remote = shlex.split(" ".join(arguments[2:]))
            self.assertEqual(["run-as", self.package, "sh", "-c"], remote[:4])
            self.assertEqual(5, len(remote))
            script = remote[4]
            prelude = "pidof() { return " + ("0" if running_at_publish else "1") + "; }\n"
            if publish_failure: prelude += "mv() { return 31; }\n"
            if truncated_input and input is not None: input = input[:-1]
            return REAL_RUN([executable, "-c", prelude + script], cwd=app, input=input, capture_output=True, timeout=10)
        return run, calls, destination

    def test_real_shell_publishes_private_file_using_stdin_only(self):
        fake, calls, destination = self.fake_adb()
        with patch.object(provision.subprocess, "run", side_effect=fake):
            provision.provision("device-one", self.package, self.credential, adb="adb")
        self.assertEqual(self.value, json.loads(destination.read_text(encoding="utf-8")))
        if os.name != "nt": self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
        self.assertEqual([], list(destination.parent.glob(".worker-access-*.tmp")))
        stream_calls = [(command, data) for command, data in calls if data is not None]
        self.assertEqual(1, len(stream_calls))
        for command, _ in calls:
            text = " ".join(command)
            self.assertNotIn(self.value["secret"], text)
            self.assertNotIn(self.value["keyId"], text)
            self.assertFalse(any(value in command for value in ("install", "start", "force-stop", "reverse", "forward")))

    def test_real_shell_failed_rename_preserves_old_file_and_cleans_temporary(self):
        fake, _, destination = self.fake_adb(publish_failure=True)
        with patch.object(provision.subprocess, "run", side_effect=fake):
            with self.assertRaises(provision.ProvisionError):
                provision.provision("device-one", self.package, self.credential, adb="adb")
        self.assertEqual(b"old-confirmed-file", destination.read_bytes())
        self.assertEqual([], list(destination.parent.glob(".worker-access-*.tmp")))

    def test_app_start_before_publication_is_refused_without_overwrite(self):
        fake, _, destination = self.fake_adb(running_at_publish=True)
        with patch.object(provision.subprocess, "run", side_effect=fake):
            with self.assertRaises(provision.ProvisionError):
                provision.provision("device-one", self.package, self.credential, adb="adb")
        self.assertEqual(b"old-confirmed-file", destination.read_bytes())
        self.assertEqual([], list(destination.parent.glob(".worker-access-*.tmp")))

    def test_truncated_stdin_preserves_previous_file(self):
        fake, _, destination = self.fake_adb(truncated_input=True)
        with patch.object(provision.subprocess, "run", side_effect=fake):
            with self.assertRaises(provision.ProvisionError):
                provision.provision("device-one", self.package, self.credential, adb="adb")
        self.assertEqual(b"old-confirmed-file", destination.read_bytes())
        self.assertEqual([], list(destination.parent.glob(".worker-access-*.tmp")))

    def test_preflight_requires_connected_installed_debuggable_stopped_app(self):
        for choice in ({"running": True}, {"run_as": False}, {"installed": False}, {"available": False}):
            with self.subTest(choice=choice):
                fake, calls, destination = self.fake_adb(**choice)
                with patch.object(provision.subprocess, "run", side_effect=fake):
                    with self.assertRaises(provision.ProvisionError):
                        provision.provision("device-one", self.package, self.credential, adb="adb")
                self.assertEqual(b"old-confirmed-file", destination.read_bytes())
                self.assertFalse(any(data is not None for _, data in calls))

    def test_unapproved_package_and_invalid_file_are_rejected_before_adb(self):
        with patch.object(provision.subprocess, "run") as run:
            with self.assertRaises(provision.ProvisionError):
                provision.provision("device-one", "other.application", self.credential, adb="adb")
            self.credential.write_text('{"secret":"private-secret"}', encoding="utf-8")
            with self.assertRaises(provision.ProvisionError):
                provision.provision("device-one", self.package, self.credential, adb="adb")
            run.assert_not_called()

    def test_cli_reports_fixed_error_without_raw_adb_or_credential_text(self):
        with patch.object(provision, "find_adb", return_value="adb"), patch.object(provision.subprocess, "run",
                side_effect=subprocess.TimeoutExpired(["adb"], 1, output=b"private-secret")), contextlib.redirect_stderr(io.StringIO()) as errors:
            result = provision.main(["--device", "device-one", "--package", self.package,
                                     "--credential-file", str(self.credential)])
        self.assertEqual(1, result)
        self.assertNotIn("private-secret", errors.getvalue())
        self.assertNotIn("Traceback", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
