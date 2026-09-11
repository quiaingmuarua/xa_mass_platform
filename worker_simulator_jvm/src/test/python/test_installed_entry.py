"""Exercise the installed CLI from a different working directory, without a Server."""
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


class InstalledEntryTest(unittest.TestCase):
    def test_single_file_controls_inventory_startup_and_relative_paths(self):
        self.assertTrue(ENTRY.is_file(), "Run :worker_simulator_jvm:installDist first")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            configuration = root / "configuration"
            working = root / "different-cwd"
            configuration.mkdir()
            working.mkdir()
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
                log_path = root / ("process-" + str(invocation) + ".log")
                with log_path.open("wb") as log:
                    process = subprocess.Popen([str(ENTRY), "--config", str(config_path)],
                                               cwd=working, stdout=log, stderr=subprocess.STDOUT,
                                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
                    try:
                        deadline = time.monotonic() + 20
                        address = None
                        while time.monotonic() < deadline:
                            output = log_path.read_text(encoding="utf-8", errors="replace")
                            match = re.search(r"WORKER_SIMULATOR_READY control=(http://[^ ]+)/lab", output)
                            if match:
                                address = match.group(1)
                                break
                            self.assertIsNone(process.poll(), output)
                            time.sleep(0.05)
                        self.assertIsNotNone(address, log_path.read_text(errors="replace"))
                        with urllib.request.urlopen(address + "/lab/v1/workers", timeout=5) as response:
                            workers = json.load(response)["workers"]
                        self.assertEqual(211, len(workers))
                        inventory = configuration / "data/scenario-workers/scenario-string-utils-workers"
                        last = json.loads((inventory / "workers-001.jsonl").read_text())["workerProperties"]
                        self.assertEqual("101", last["labSlot"])
                        self.assertEqual("string-utils", last["capability"])
                        sims = [json.loads(line)["workerProperties"] for line in
                                (inventory.parent / "demo-sim/workers-000.jsonl").read_text().splitlines()]
                        self.assertEqual(60, len(sims))
                        self.assertEqual("861700000001", sims[0]["phone"])
                        self.assertEqual("861700000060", sims[-1]["phone"])
                        self.assertEqual(["CN", "US", "GB"], [item["country"] for item in sims[:3]])
                        self.assertEqual("true", sims[0]["messaging.enabled"])
                        self.assertFalse((working / "data").exists())
                    finally:
                        if process.poll() is None:
                            if os.name == "nt":
                                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                               check=False, stdout=subprocess.DEVNULL,
                                               stderr=subprocess.DEVNULL,
                                               creationflags=subprocess.CREATE_NO_WINDOW)
                            else:
                                process.terminate()
                            process.wait(timeout=5)
                config["workerGroups"]["scenario-string-utils-workers"].update(
                    count=1, propertiesTemplate={"labSlot": "replacement must not run"})


if __name__ == "__main__":
    unittest.main()
