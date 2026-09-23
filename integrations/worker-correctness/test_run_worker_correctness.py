"""Contract checks for process-owned live Properties evidence and Prepare auditing."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import run_worker_correctness as runner


PREPARE = b"POST /api/v1/worker-groups/string/workers:prepare-batch 200\n"
READ = b"POST /api/v1/runtime-view/worker-groups/string/workers:preview 200\n"


class AccessLogTest(unittest.TestCase):
    def test_counts_both_prepare_routes_including_failed_attempts(self):
        extra = (b"POST /api/v1/worker-groups/phone/workers:prepare 409\n"
                 b"POST /api/v1/worker-groups/string/workers:prepare-batch 500\n")
        audit = runner._prepare_audit(PREPARE + READ, PREPARE + READ + extra)
        self.assertEqual({"prepare": 1, "prepareBatch": 1}, audit["requestDelta"])

    def test_observation_routes_do_not_count_and_initial_prepare_is_required(self):
        self.assertEqual({"prepare": 0, "prepareBatch": 0},
                         runner._prepare_audit(PREPARE, PREPARE + READ)["requestDelta"])
        with self.assertRaises(RuntimeError):
            runner._prepare_audit(READ, READ + READ)

    def test_missing_malformed_truncated_and_rotated_records_fail_closed(self):
        for data in (b"", PREPARE.rstrip(), b"unexpected access format\n", b"POST /path 999\n"):
            with self.subTest(data=data), self.assertRaises(RuntimeError):
                runner._prepare_counts(data)
        with self.assertRaises(RuntimeError):
            runner._prepare_audit(PREPARE + READ, PREPARE)
        with self.assertRaises(RuntimeError):
            runner._prepare_audit(PREPARE, READ + PREPARE)


class LivePhaseTest(unittest.TestCase):
    def run_phase(self, *, extra=READ, host_exited=False, changed_file=False, harness_failed=False):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "evidence").mkdir()
            path = output / "evidence/worker-correctness-live-properties.json"
            host = Mock(pid=123)
            host.poll.side_effect = [None, 1 if host_exited else None]
            server = Mock(pid=321)
            server.poll.return_value = None

            def harness(*args):
                path.write_text(json.dumps({"harnessStatus": "failed" if harness_failed else "succeeded",
                                            "status": "pending-runner-audit"}))
                if harness_failed:
                    raise RuntimeError("Harness failed")

            with patch.object(runner, "_read_access", side_effect=[PREPARE, PREPARE + extra]), \
                 patch.object(runner, "_control_records", side_effect=[{"control": "same"},
                             {"control": "changed" if changed_file else "same"}]), \
                 patch.object(runner, "_run_phase", side_effect=harness) as call:
                try:
                    runner._run_live_properties("proof", output / "data", output / "initial.json",
                                                output, host, server, SimpleNamespace(), {})
                    failed = False
                except RuntimeError:
                    failed = True
                self.assertEqual(1, call.call_count)
                return failed, json.loads(path.read_text())

    def test_success_requires_process_file_and_prepare_audits(self):
        failed, evidence = self.run_phase()
        self.assertFalse(failed)
        self.assertEqual("succeeded", evidence["status"])
        self.assertTrue(evidence["hostProcessUnchanged"])
        self.assertTrue(evidence["controlFileRecordsUnchanged"])
        self.assertEqual({"prepare": 0, "prepareBatch": 0}, evidence["prepareAudit"]["requestDelta"])

    def test_each_audit_failure_and_harness_failure_gates_the_lane(self):
        for options in ({"extra": PREPARE}, {"host_exited": True}, {"changed_file": True},
                        {"harness_failed": True}):
            with self.subTest(options=options):
                failed, evidence = self.run_phase(**options)
                self.assertTrue(failed)
                self.assertEqual("failed", evidence["status"])


if __name__ == "__main__":
    unittest.main()
