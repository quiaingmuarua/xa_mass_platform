from __future__ import annotations

import argparse
import copy
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch

import run_android_emulator_worker as proof


class EmulatorChoreographyTest(unittest.TestCase):
    def runner(self, root: Path) -> proof.ProofRunner:
        return proof.ProofRunner(argparse.Namespace(
            android_serial="emulator-5554", proof_root=root,
            runtime_archive=root / "runtime.zip", sdk_archive=root / "sdk.zip",
            version="3.0.0", commit="a" * 40, runtime_sha256="b" * 64, sdk_sha256="c" * 64,
            apk_root=root / "apks",
            proof_runtime=root / "jars", redis_url="redis://127.0.0.1:6379/15",
            redis_scope="test_owned", maximum_wait_millis=180000))

    def test_checkpoint_cannot_succeed_after_child_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            process = Mock()
            process.poll.return_value = 1
            with self.assertRaisesRegex(RuntimeError, "exited before checkpoint"):
                proof.await_marker(process, Path(directory) / "output", "READY", 1)

    def test_invalid_archive_input_fails_before_any_adb_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.adb = Mock()
            with patch.object(proof.consumer, "prepare_options", side_effect=ValueError("SHA256 mismatch")), \
                    self.assertRaisesRegex(ValueError, "SHA256"):
                runner.preflight()
            runner.adb.assert_not_called()

    def test_provenance_cannot_substitute_a_different_archive_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.options.apk_root.mkdir()
            (runner.options.apk_root / "apk-provenance.json").write_text('{}')
            with patch.object(proof.consumer, "prepare_options", return_value={"version": "3.0.0"}), \
                    self.assertRaisesRegex(RuntimeError, "provenance"):
                runner.validate_inputs()

    def test_apk_and_resolved_sdk_bytes_are_checked_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.options.apk_root.mkdir()
            receipt = dict(version=runner.options.version, gitCommit=runner.options.commit,
                           archives={}, serverJarSha256="d" * 64,
                           sdkArtifacts={name: {"sha256": "e" * 64} for name in proof.consumer.ARTIFACTS})
            hashes = {}
            for name in proof.APK_NAMES:
                path = runner.options.apk_root / name
                path.write_bytes(name.encode())
                hashes[name] = proof.consumer.digest(path)
            resolved = {name: dict(version=runner.options.version, sha256="e" * 64)
                        for name in proof.consumer.ARTIFACTS}
            provenance = dict(receipt, runtimeApiBaseUrl="http://127.0.0.1:18082", projectId="default",
                              caller=dict(userId="1000", role="admin"), profile="consumer", pacerPreset="DEFAULT",
                              endpointManagerId="runtime-websocket", independentBuild=True, apkSha256=hashes,
                              sdkConsumption=dict(version=runner.options.version, gitCommit=runner.options.commit,
                                  sdkArchiveSha256=runner.options.sdk_sha256,
                                  runtimeApiBaseUrl="http://127.0.0.1:18082",
                                  variants={variant: copy.deepcopy(resolved) for variant in ("debug", "lab1", "lab2", "lab3")}))
            path = runner.options.apk_root / "apk-provenance.json"
            runner.adb = Mock()
            with patch.object(proof.consumer, "prepare_options", return_value=receipt):
                path.write_text(json.dumps(provenance))
                runner.validate_inputs()
                invalid = copy.deepcopy(provenance)
                invalid["sdkConsumption"]["variants"]["lab2"].pop(next(iter(resolved)))
                path.write_text(json.dumps(invalid))
                with self.assertRaisesRegex(RuntimeError, "resolved SDK bytes"):
                    runner.preflight()
                path.write_text(json.dumps(provenance))
                (runner.options.apk_root / proof.APK_NAMES[0]).write_bytes(b"different APK")
                with self.assertRaisesRegex(RuntimeError, "APK bytes"):
                    runner.preflight()
            runner.adb.assert_not_called()

    def test_checkpoint_timeout_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            process = Mock()
            process.poll.return_value = None
            with patch.object(proof.time, "monotonic", side_effect=[0, 0, 2]), \
                    patch.object(proof.time, "sleep"), \
                    self.assertRaises(TimeoutError):
                proof.await_marker(process, Path(directory) / "output", "READY", 1)

    def test_phase_rejects_child_failure_and_keeps_output(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            process = Mock()
            process.wait.return_value = 1
            with patch.object(proof, "spawn", return_value=process), \
                    self.assertRaisesRegex(RuntimeError, "Java proof"):
                runner.phase("Correctness", "proof", "initial", "initial")
            self.assertTrue((Path(directory) / "initial.out").is_file())
            runner.cleanup()

    def test_all_mains_use_the_existing_shared_option_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            process = Mock()
            process.wait.return_value = 0
            with patch.object(proof, "spawn", return_value=process) as start:
                runner.phase("Correctness", "proof", "initial", "single")
                single = start.call_args.args[0]
                runner.phase("TriadCorrectness", "proof", "initial", "triad")
                triad = start.call_args.args[0]
            self.assertIn("--device-base-url=http://127.0.0.1:18084", single)
            self.assertFalse(any(argument.startswith("--lab") for argument in single))
            self.assertIn("--device-base-url=http://127.0.0.1:18084", triad)
            self.assertFalse(any(argument.startswith("--lab") for argument in triad))
            runner.cleanup()

    def test_exception_cleanup_releases_only_owned_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.proof = Mock()
            runner.touched_apps.add(proof.APPLICATIONS[0])
            runner.mappings = [("forward", 18084)]
            runner.scopes = {"test_owned_correctness"}
            runner.adb = Mock()
            runner.stop_app = Mock()
            runner.stop_server = Mock()
            runner.restore_freezer = Mock()
            with patch.object(proof, "stop_process") as stop, \
                    patch.object(proof, "cleanup_scope") as clear:
                runner.cleanup()
                stop.assert_called_once_with(runner.proof)
                clear.assert_called_once_with(runner.options.redis_url, "test_owned_correctness")
            runner.adb.assert_called_once_with("forward", "--remove", "tcp:18084")
            runner.stop_app.assert_called_once_with(proof.APPLICATIONS[0])
            runner.restore_freezer.assert_called_once()

    def test_cleanup_continues_after_process_failure_but_reports_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.stop_server = Mock(side_effect=RuntimeError("server"))
            runner.restore_freezer = Mock()
            runner.scopes.add("test_owned")
            with patch.object(proof, "cleanup_scope") as clear, \
                    self.assertRaisesRegex(RuntimeError, "cleanup did not finish"):
                runner.cleanup()
            clear.assert_called_once()
            runner.restore_freezer.assert_called_once()

    def test_adb_always_addresses_selected_device(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            with patch.object(proof.subprocess, "run") as run:
                run.return_value.stdout = "device\n"
                run.return_value.returncode = 0
                self.assertEqual("device", runner.adb("get-state"))
            self.assertEqual(["adb", "-s", "emulator-5554", "get-state"], run.call_args.args[0])

    def test_adb_failure_retains_install_diagnostic(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            with patch.object(proof.subprocess, "run") as run:
                run.return_value.returncode = 1
                run.return_value.stderr = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"
                with self.assertRaisesRegex(RuntimeError, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
                    runner.adb("install", "-r", "demo.apk")

    def test_restores_absent_original_freezer_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            runner.freezer_original = ("null", "null")
            runner.adb = Mock()
            runner.wait_boot = Mock()
            runner.restore_freezer()
            self.assertEqual([
                call("shell", "device_config", "delete", "activity_manager_native_boot", "use_freezer"),
                call("shell", "settings", "delete", "global", "cached_apps_freezer"),
                call("reboot"),
                call("wait-for-disconnect", timeout=60),
            ], runner.adb.call_args_list)
            runner.wait_boot.assert_called_once()

    def test_reboot_fences_the_old_transport_before_reading_boot_state(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = self.runner(Path(directory))
            steps = Mock()
            runner.adb = steps.adb
            runner.wait_boot = steps.wait_boot
            runner.reboot()
            self.assertEqual([call.adb("reboot"), call.adb("wait-for-disconnect", timeout=60),
                              call.wait_boot()], steps.mock_calls)


if __name__ == "__main__":
    unittest.main()
