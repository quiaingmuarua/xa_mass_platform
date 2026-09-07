import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("dynamic_runner", Path(__file__).with_name("run_worker_dynamic_matching.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def test_access_audit_rejects_missing_rotated_partial_and_failed_prepare(self):
        before = b"POST /api/v1/worker-groups/g/workers:prepare-batch 200\n"
        self.assertEqual({"prepare": 0, "prepareBatch": 0}, runner.audit_prepare(before, before)["requestDelta"])
        for after in (b"", before[:-1], b"GET /ready 200\n", before + b"GET /api/v1/worker-groups/g/workers:prepare 409\n"):
            with self.assertRaises(ValueError):
                runner.audit_prepare(before, after)
        with self.assertRaises(ValueError):
            runner.prepare_counts(b"GET /ready secret 200\n")

    def test_world_has_exact_mutable_and_control_sets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = runner.materialize(root)
            self.assertEqual(100, len(rows))
            self.assertEqual(10, sum(row["properties"].get("proofTarget") == "yes" for row in rows))
            self.assertEqual(90, len(runner.control_records(root)))
            self.assertTrue(all(row["properties"]["emptySentinel"] == "" for row in rows))
            self.assertEqual(100, len({(row["group"], row["key"]) for row in rows}))


if __name__ == "__main__":
    unittest.main()
