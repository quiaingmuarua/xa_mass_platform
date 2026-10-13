from __future__ import annotations

import argparse
import tempfile
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch

import run_android_emulator_worker as proof


class EmulatorChoreographyTest(unittest.TestCase):
    def runner(self, root: Path) -> proof.ProofRunner:
        return proof.ProofRunner(argparse.Namespace(
            android_serial="emulator-5554", proof_root=root,
            server_jar=root / "server.jar", apk_root=root / "apks",
            proof_runtime=root / "jars", redis_url="redis://127.0.0.1:6379/15",
            redis_scope="test_owned", maximum_wait_millis=180000))

    def test_checkpoint_cannot_succeed_after_child_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            process = Mock()
            process.poll.return_value = 1
            with self.assertRaisesRegex(RuntimeError, "exited before checkpoint"):
                proof.await_marker(process, Path(directory) / "output", "READY", 1)

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
                self.assertEqual("device", runner.adb("get-state"))
            self.assertEqual(["adb", "-s", "emulator-5554", "get-state"], run.call_args.args[0])

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
            ], runner.adb.call_args_list)
            runner.wait_boot.assert_called_once()


if __name__ == "__main__":
    unittest.main()
