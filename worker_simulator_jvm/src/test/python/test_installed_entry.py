"""Exercise the installed CLI from different working directories, without a Server."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
import urllib.request

MODULE = Path(__file__).resolve().parents[3]
ENTRY = MODULE / "build/install/xa-mass-worker-simulator/bin" / (
    "xa-mass-worker-simulator.bat" if os.name == "nt" else "xa-mass-worker-simulator")


@contextmanager
def running(config_path, working, log_path):
    with log_path.open("wb") as log:
        process = subprocess.Popen([str(ENTRY), "--config", str(config_path)],
                                   cwd=working, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                output = log_path.read_text(encoding="utf-8", errors="replace")
                match = re.search(r"WORKER_SIMULATOR_READY control=(http://[^ ]+)/lab", output)
                if match:
                    yield match.group(1)
                    return
                if process.poll() is not None:
                    raise AssertionError(output)
                time.sleep(0.05)
            raise AssertionError(log_path.read_text(errors="replace"))
        finally:
            if process.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
                else:
                    process.terminate()
                process.wait(timeout=5)


def properties(root, group="demo-sim"):
    return [json.loads(line)["workerProperties"] for path in sorted((root / group).glob("*.jsonl"))
            for line in path.read_text(encoding="utf-8").splitlines()]


class InstalledEntryTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(ENTRY.is_file(), "Run :worker_simulator_jvm:installDist first")

    def test_single_file_controls_inventory_startup_and_relative_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            configuration, working = root / "configuration", root / "different-cwd"
            configuration.mkdir(); working.mkdir()
            config_path = configuration / "simulator.json"
            config = {
                "controlPort": 0,
                "workerGroups": {
                    "scenario-string-utils-workers": {"count": 101},
                    "scenario-phone-number-workers": {},
                    "demo-sim": {}},
                "startupPlan": {"initialWorkers": [], "scheduledStops": []},
            }
            for invocation in range(2):
                config_path.write_text(json.dumps(config), encoding="utf-8")
                with running(config_path, working, root / f"process-{invocation}.log") as address:
                    with urllib.request.urlopen(address + "/lab/v1/workers", timeout=5) as response:
                        workers = json.load(response)["workers"]
                    self.assertEqual(211, len(workers))
                    inventory = configuration / "data/scenario-workers"
                    last = properties(inventory, "scenario-string-utils-workers")[-1]
                    self.assertEqual("101", last["labSlot"])
                    self.assertEqual("string-utils", last["capability"])
                    self.assertEqual(("workers-001.jsonl", "1"), (last["labInventoryKey"], last["labInventoryLine"]))
                    sims = properties(inventory)
                    self.assertEqual(60, len(sims))
                    self.assertEqual("861700000001", sims[0]["phone"])
                    self.assertEqual("861700000060", sims[-1]["phone"])
                    self.assertEqual(["CN", "US", "CN"], [item["country"] for item in sims[:3]])
                    self.assertEqual("true", sims[0]["messaging.enabled"])
                    self.assertFalse((working / "data").exists())
                config["seed"] = 712
                config["workerGroups"]["scenario-string-utils-workers"].update(
                    count=1, propertiesTemplate={"labSlot": "replacement must not run"})

    def test_seed_reproduces_across_processes_and_paths_but_only_rebuild_changes_retained_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = {
                "seed": 712, "controlPort": 0,
                "workerGroups": {"demo-sim": {"count": 101, "propertiesTemplate": {
                    "phone": {"$index": [861700000001, 1]}, "country": {"$choice": ["CN", "US", "GB"]},
                    "app": {"$choice": ["IG", "TG", "WA"]}, "battery": {"$range": [0, 1000]},
                    "messaging.enabled": "true"}}},
                "startupPlan": {"initialWorkers": [], "scheduledStops": []},
            }
            worlds = []
            for name in ("first", "second"):
                configuration, working = root / name, root / (name + "-cwd")
                configuration.mkdir(); working.mkdir()
                config_path = configuration / "simulator.json"
                config_path.write_text(json.dumps(config), encoding="utf-8")
                with running(config_path, working, root / (name + ".log")):
                    worlds.append(properties(configuration / "data/scenario-workers"))
                self.assertFalse((working / "data").exists())
            self.assertEqual(worlds[0], worlds[1])
            self.assertEqual(("GB", "IG", "448"), tuple(worlds[0][0][k] for k in ("country", "app", "battery")))
            self.assertEqual(("CN", "TG", "551"), tuple(worlds[0][100][k] for k in ("country", "app", "battery")))
            self.assertEqual(("workers-001.jsonl", "1"),
                             tuple(worlds[0][100][k] for k in ("labInventoryKey", "labInventoryLine")))
            config_path = root / "first/simulator.json"
            inventory = root / "first/data/scenario-workers"
            path = inventory / "demo-sim/workers-000.jsonl"
            documents = [json.loads(line) for line in path.read_text().splitlines()]
            documents[0]["workerProperties"]["battery"] = "local edit"
            path.write_text("\n".join(json.dumps(item) for item in documents) + "\n", encoding="utf-8")
            retained = properties(inventory)
            config["seed"] = 713
            config["workerGroups"]["demo-sim"]["count"] = 1
            config_path.write_text(json.dumps(config), encoding="utf-8")
            with running(config_path, root / "second-cwd", root / "reuse.log"):
                self.assertEqual(retained, properties(inventory))
            config["workerGroups"]["demo-sim"].update(count=101, newEnvironment=True)
            config_path.write_text(json.dumps(config), encoding="utf-8")
            with running(config_path, root / "first-cwd", root / "reset.log"):
                rebuilt = properties(inventory)
                self.assertNotEqual(worlds[0], rebuilt)
                self.assertNotEqual("local edit", rebuilt[0]["battery"])
                self.assertEqual([p["phone"] for p in worlds[0]], [p["phone"] for p in rebuilt])
                self.assertEqual([(p["labInventoryKey"], p["labInventoryLine"]) for p in worlds[0]],
                                 [(p["labInventoryKey"], p["labInventoryLine"]) for p in rebuilt])


if __name__ == "__main__":
    unittest.main()
