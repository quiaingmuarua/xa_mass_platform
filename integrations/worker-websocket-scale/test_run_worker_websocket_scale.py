import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("run_worker_websocket_scale.py")
SPEC = importlib.util.spec_from_file_location("worker_websocket_scale_runner", MODULE_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


class WorkerWebSocketScaleRunnerTest(unittest.TestCase):

    def test_inventory_uses_at_most_one_hundred_strict_records_per_file(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            RUNNER._generate_inventory(sandbox, 205)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual([100, 100, 5], [len(path.read_text().splitlines()) for path in files])
            observed = 0
            for path in files:
                for line_number, line in enumerate(path.read_text().splitlines(), 1):
                    value = json.loads(line)
                    self.assertEqual(2, value["schemaVersion"])
                    properties = value["workerProperties"]
                    self.assertEqual(path.name, properties["labInventoryKey"])
                    self.assertEqual(line_number, properties["labInventoryLine"])
                    observed += 1
            self.assertEqual(205, observed)

    def test_capability_assembly_keeps_only_the_existing_md5_capability(self):
        assembly = RUNNER._capability_assembly()
        self.assertEqual([RUNNER.WORKER_GROUP], list(assembly))
        group = assembly[RUNNER.WORKER_GROUP]
        self.assertEqual(["extension.worker.string.md5"], group["eventCodes"])
        self.assertEqual(600, group["reconnectPolicy"]["maxUnstableAttempts"])


if __name__ == "__main__":
    unittest.main()
