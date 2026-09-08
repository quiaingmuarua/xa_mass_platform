import importlib.util
import json
from collections import Counter
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
            self.assertEqual(1000, len(rows))
            self.assertEqual({runner.STRING_GROUP: 500, runner.PHONE_GROUP: 500}, Counter(row["group"] for row in rows))
            self.assertEqual(100, sum(row["properties"].get("proofTarget") == "yes" for row in rows))
            self.assertEqual(900, len(runner.control_records(root)))
            self.assertTrue(all(row["properties"]["emptySentinel"] == "" for row in rows))
            self.assertEqual(1000, len({(row["group"], row["key"]) for row in rows}))
            string_rows = [row for row in rows if row["group"] == runner.STRING_GROUP]
            self.assertEqual({("A", "no"): 200, ("B", "no"): 200, ("A", "yes"): 100},
                             Counter((r["properties"]["proofPool"], r["properties"]["proofTarget"]) for r in string_rows))
            self.assertEqual(5, len(list((root / runner.STRING_GROUP).glob("*.jsonl"))))

    def test_control_audit_covers_each_file_and_uses_immutable_coordinates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner.materialize(root)
            for index in range(5):
                path = root / runner.STRING_GROUP / f"workers-{index:03d}.jsonl"
                lines = path.read_text(encoding="utf-8").splitlines()
                rows = [json.loads(line) for line in lines]
                self.assertEqual(20, sum(r["workerProperties"]["proofTarget"] == "yes" for r in rows))
                baseline = runner.control_records(root)
                rows[80]["workerProperties"]["proofPool"] = "B"
                lines[80] = json.dumps(rows[80])
                path.write_text("\n".join(lines) + "\n", encoding="utf-8")
                self.assertEqual(baseline, runner.control_records(root))
                rows[79]["workerProperties"]["proofTarget"] = "yes"
                lines[79] = json.dumps(rows[79])
                path.write_text("\n".join(lines) + "\n", encoding="utf-8")
                changed = runner.control_records(root)
                self.assertEqual(baseline.keys(), changed.keys())
                self.assertEqual({f"{runner.STRING_GROUP}/{path.name}:80"},
                                 {key for key in baseline if baseline[key] != changed[key]})


if __name__ == "__main__":
    unittest.main()
