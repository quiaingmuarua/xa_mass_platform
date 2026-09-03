import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run_worker_convergence_health.py")
SPEC = importlib.util.spec_from_file_location(
    "worker_convergence_health",
    SCRIPT,
)
PROOF = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROOF)


class WorkerConvergenceHealthRunnerTest(unittest.TestCase):

    def test_startup_plan_is_strict_and_deterministic(self):
        plan = PROOF._startup_plan(
            (PROOF.FAULT_TARGET,),
            ((PROOF.FAULT_TARGET, 5000),),
        )
        self.assertEqual(
            {
                "schemaVersion": 1,
                "initialWorkers": [
                    {
                        "workerGroupId": PROOF.STRING_GROUP,
                        "labWorkerKey": PROOF.FAULT_TARGET[1],
                    }
                ],
                "scheduledStops": [
                    {
                        "workerGroupId": PROOF.STRING_GROUP,
                        "labWorkerKey": PROOF.FAULT_TARGET[1],
                        "delayMillis": 5000,
                    }
                ],
            },
            plan,
        )

    def test_capability_assembly_survives_one_server_restart(self):
        assembly = PROOF._capability_assembly()

        self.assertEqual(
            {PROOF.PHONE_GROUP, PROOF.STRING_GROUP},
            set(assembly),
        )
        for group in assembly.values():
            self.assertEqual(
                600,
                group["reconnectPolicy"]["maxUnstableAttempts"],
            )
            self.assertEqual(
                500,
                group["reconnectPolicy"]["reconnectIntervalMillis"],
            )
        self.assertIn(
            "extension.worker.lab.checkpoint",
            assembly[PROOF.STRING_GROUP]["eventCodes"],
        )
        self.assertIn(
            "extension.worker.lab.delay",
            assembly[PROOF.STRING_GROUP]["eventCodes"],
        )
        self.assertIn(
            "extension.worker.lab.fail",
            assembly[PROOF.STRING_GROUP]["eventCodes"],
        )
        self.assertNotIn(
            "extension.worker.lab.delay",
            assembly[PROOF.PHONE_GROUP]["eventCodes"],
        )

    def test_server_catalog_matches_convergence_capability_assembly(self):
        config = (
            PROOF.SERVER_CONFIG_DIRECTORY
            / "application-scenario-workers.yaml"
        ).read_text(encoding="utf-8")

        self.assertIn('"extension.worker.lab.delay"', config)
        self.assertIn('"extension.worker.lab.fail"', config)
        phone, string = config.split(
            '"scenario-string-utils-workers"',
            maxsplit=1,
        )
        self.assertNotIn('"extension.worker.lab.delay"', phone)
        self.assertIn('"extension.worker.lab.delay"', string)

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
                / "worker-convergence-in-flight-loss-convergence-summary.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual("in-flight-loss-convergence", summary["lane"])
            self.assertEqual("proof-not-established", summary["failureKind"])


if __name__ == "__main__":
    unittest.main()
