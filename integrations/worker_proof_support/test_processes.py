"""Exercise owned processes and bounded readiness without a Runtime or Redis."""
import os
from contextlib import ExitStack
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from integrations.worker_proof_support import processes


class ProcessTest(unittest.TestCase):
    def test_spawn_uses_explicit_directory_environment_and_closes_parent_log(self):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as cleanup:
            root = Path(directory)
            log = root / "logs/child.log"
            child = processes.start_process(
                [sys.executable, "-c", "import os; print(os.getcwd()); print(os.environ['PROOF_VALUE'])"],
                log, dict(os.environ, PROOF_VALUE="fixture"), cwd=root,
            )
            cleanup.callback(processes.stop_process, child, force=True)
            self.assertEqual(0, child.wait(timeout=10))
            self.assertEqual([str(root), "fixture"], log.read_text().splitlines())
            processes.stop_process(child, force=False)
            processes.stop_process(child, force=True)
            log.unlink()  # The parent must not retain an open handle on Windows.

    def test_spawn_failure_closes_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "failed.log"
            with patch.object(processes.subprocess, "Popen", side_effect=OSError("fixture")):
                with self.assertRaises(OSError):
                    processes.start_process(["missing"], log, {}, cwd=root)
            log.unlink()

    def test_force_stop_does_not_stop_another_owned_child(self):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as cleanup:
            root = Path(directory)
            command = [sys.executable, "-c", "import time; time.sleep(60)"]
            children = []
            for n in range(2):
                child = processes.start_process(command, root / f"{n}.log", dict(os.environ), cwd=root)
                children.append(child)
                cleanup.callback(processes.stop_process, child, force=True)
            processes.stop_process(children[0], force=True)
            self.assertIsNotNone(children[0].poll())
            self.assertIsNone(children[1].poll())

    def test_windows_timeout_escalates_only_the_supplied_tree(self):
        child = Mock(pid=1234)
        child.poll.return_value = None
        child.wait.side_effect = [subprocess.TimeoutExpired("fixture", 15), 0]
        with patch.object(processes.os, "name", "nt"), patch.object(processes.subprocess, "run") as run:
            processes.stop_process(child, force=False)
        self.assertEqual(["taskkill", "/PID", "1234", "/T"], run.call_args_list[0].args[0])
        self.assertEqual(["taskkill", "/PID", "1234", "/T", "/F"], run.call_args_list[1].args[0])

    def test_posix_timeout_escalates_only_the_supplied_group(self):
        child = Mock(pid=1234)
        child.poll.return_value = None
        child.wait.side_effect = [subprocess.TimeoutExpired("fixture", 15), 0]
        with patch.object(processes.os, "name", "posix"), \
             patch.object(processes.os, "killpg", create=True) as killpg, \
             patch.object(processes.signal, "SIGKILL", 9, create=True):
            processes.stop_process(child, force=False)
        self.assertEqual([(1234, processes.signal.SIGTERM), (1234, 9)],
                         [call.args for call in killpg.call_args_list])

    def test_ready_returns_without_mutating_process(self):
        child = Mock()
        child.poll.return_value = None
        response = Mock()
        response.__enter__ = Mock(return_value=Mock(status=204))
        response.__exit__ = Mock()
        with patch.object(processes.urllib.request, "urlopen", return_value=response):
            processes.wait_http("http://fixture/ready", child, 1000, Path("unused"))
        child.terminate.assert_not_called()

    def test_early_exit_and_readiness_timeout_include_bounded_log_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "child.log"
            log.write_text("\n".join(str(n) for n in range(100)), encoding="utf-8")
            child = Mock(returncode=3)
            child.poll.return_value = 3
            with self.assertRaisesRegex(RuntimeError, "Process exited with 3"):
                processes.wait_http("http://fixture", child, 1000, log)
            child.poll.return_value = None
            with patch.object(processes.time, "monotonic", side_effect=[0, 0, 2]), \
                 patch.object(processes.time, "sleep"), \
                 patch.object(processes.urllib.request, "urlopen", side_effect=OSError("offline")):
                with self.assertRaisesRegex(RuntimeError, "Timed out waiting"):
                    processes.wait_http("http://fixture", child, 1000, log)
            self.assertEqual(80, len(processes.tail(log).splitlines()))
            self.assertEqual("20", processes.tail(log).splitlines()[0])


if __name__ == "__main__":
    unittest.main()
