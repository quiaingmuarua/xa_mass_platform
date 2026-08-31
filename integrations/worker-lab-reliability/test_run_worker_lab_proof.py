import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run_worker_lab_proof.py")
SPEC = importlib.util.spec_from_file_location("worker_lab_proof", SCRIPT)
PROOF = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROOF)


class WorkerLabProofRunnerTest(unittest.TestCase):

    def test_startup_plan_is_strict_and_deterministic(self):
        plan = PROOF._startup_plan(
            (PROOF.STRING_ONE,),
            ((PROOF.STRING_ONE, 5000),),
        )
        self.assertEqual(
            {
                "schemaVersion": 1,
                "initialWorkers": [
                    {
                        "workerGroupId": PROOF.STRING_GROUP,
                        "clientWorkerKey": PROOF.STRING_ONE[1],
                    }
                ],
                "scheduledStops": [
                    {
                        "workerGroupId": PROOF.STRING_GROUP,
                        "clientWorkerKey": PROOF.STRING_ONE[1],
                        "delayMillis": 5000,
                    }
                ],
            },
            plan,
        )

    def test_offline_property_replace_preserves_state_document(self):
        with tempfile.TemporaryDirectory() as temporary:
            sandbox = Path(temporary)
            path = sandbox / PROOF.STRING_GROUP / f"{PROOF.STRING_TWO[1]}.json"
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 2,
                        "workerProperties": {"runtime": "java", "labSlot": 2},
                    }
                ),
                encoding="utf-8",
            )

            PROOF._replace_worker_lab_slot(sandbox, PROOF.STRING_TWO, 1)

            value = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(2, value["schemaVersion"])
            self.assertEqual("java", value["workerProperties"]["runtime"])
            self.assertEqual(1, value["workerProperties"]["labSlot"])

    def test_redis_response_parser_handles_scan_shape(self):
        response = PROOF._read_redis_response(io.BytesIO(
            b"*2\r\n$1\r\n0\r\n*2\r\n$5\r\nkey-a\r\n$5\r\nkey-b\r\n"
        ))

        self.assertEqual([b"0", [b"key-a", b"key-b"]], response)

    def test_redis_cleanup_rejects_non_test_scope_before_connecting(self):
        with self.assertRaisesRegex(ValueError, "exact test_\\* scope"):
            PROOF._cleanup_scope("redis://127.0.0.1:1/0", "production")

    def test_runner_failure_uses_the_canonical_lane_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            evidence = Path(temporary)

            PROOF._write_runner_failure(
                evidence,
                "task-fault",
                "proof-1",
                RuntimeError("host failed"),
            )

            summary = json.loads((
                evidence
                / "worker-lab-task-fault-convergence-summary.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual("task-fault-convergence", summary["lane"])
            self.assertEqual("proof-not-established", summary["failureKind"])


if __name__ == "__main__":
    unittest.main()
