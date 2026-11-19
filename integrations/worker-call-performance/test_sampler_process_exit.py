"""Real Linux process states behind the sampler's terminal-thread guard."""
import importlib.util
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("performance_exit_race", Path(__file__).with_name("run_worker_call_performance.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


@unittest.skipUnless(sys.platform == "linux" and hasattr(os, "WNOWAIT"), "Linux /proc and waitid required")
class SamplerProcessExitTest(unittest.TestCase):
    def denied_sample(self, pid):
        with tempfile.TemporaryDirectory() as directory:
            sampler = runner.Sampler(Path(directory) / "resources.jsonl", None)
            observer = Mock(pid=pid)
            observer.poll.return_value = None
            observer.wait.side_effect = subprocess.TimeoutExpired("delayed-parent-reap", .05)
            sampler.register("harness-case", observer)
            def denied(_):
                sampler.stopped.set()
                raise PermissionError("FD access revoked")
            with patch.object(runner, "process_sample", side_effect=denied):
                sampler.run()
            observer.wait.assert_called_once_with(timeout=.05)
            self.assertEqual(0, sampler.counts["harness-case"])
            return sampler.failure

    def test_unreaped_terminal_child_does_not_invalidate_completed_work(self):
        child = subprocess.Popen([sys.executable, "-c", "import os; os._exit(0)"])
        try:
            os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOWAIT)
            self.assertEqual(("Z", 1), runner.process_exit_state(child.pid))
            self.assertIsNone(self.denied_sample(child.pid))
        finally:
            self.assertEqual(0, child.wait(timeout=5))

    def test_zombie_leader_with_a_live_worker_thread_still_fails_sampling(self):
        script = """import ctypes, os, sys, threading
def worker():
    print('worker-running', flush=True)
    sys.stdin.buffer.read(1)
    os._exit(0)
threading.Thread(target=worker).start()
libc = ctypes.CDLL(None)
libc.pthread_exit.argtypes = [ctypes.c_void_p]
libc.pthread_exit.restype = None
libc.pthread_exit(None)
"""
        child = subprocess.Popen([sys.executable, "-c", script], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        try:
            self.assertTrue(select.select([child.stdout], [], [], 5)[0])
            self.assertEqual(b"worker-running\n", child.stdout.readline())
            deadline = time.monotonic() + 5
            state = runner.process_exit_state(child.pid)
            while (state is None or state[0] != "Z") and time.monotonic() < deadline:
                time.sleep(.01)
                state = runner.process_exit_state(child.pid)
            self.assertIsNotNone(state)
            self.assertEqual("Z", state[0])
            self.assertGreater(state[1], 1)
            self.assertIsNone(child.poll())
            self.assertIn("PermissionError", self.denied_sample(child.pid))
        finally:
            try:
                child.communicate(input=b"x", timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.communicate(timeout=5)
