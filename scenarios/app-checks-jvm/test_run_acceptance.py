import unittest
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import url2pathname
from unittest.mock import patch
from run_acceptance import hash_value, request, regression_window_configuration


class AppCheckProofTest(unittest.TestCase):
    def read_seed(self):
        path = Path(url2pathname(urlparse(os.environ["XA_MASS_APP_CHECKS_SEED"]).path))
        return path, json.loads(path.read_text(encoding="utf-8"))

    def test_window_fixture_keeps_other_settings_and_restores_environment_on_failure(self):
        original = json.dumps({"some.setting": "retained"})
        with patch.dict(os.environ, {"SPRING_APPLICATION_JSON": original}):
            with self.assertRaises(RuntimeError):
                with regression_window_configuration():
                    actual = json.loads(os.environ["SPRING_APPLICATION_JSON"])
                    self.assertEqual("retained", actual["some.setting"])
                    path, configuration = self.read_seed()
                    for group in ("app-a-sim", "app-b-sim"):
                        self.assertEqual({"windowMillis": 60000, "maxCount": 1000}, configuration["poolGroups"][group])
                    self.assertEqual({"some.setting": "retained"}, actual)
                    raise RuntimeError("scenario failed")
            self.assertEqual(original, os.environ["SPRING_APPLICATION_JSON"])
            self.assertFalse(path.exists())
        with patch.dict(os.environ):
            os.environ.pop("SPRING_APPLICATION_JSON", None)
            with regression_window_configuration():
                self.assertIn("XA_MASS_APP_CHECKS_SEED", os.environ)
            self.assertNotIn("SPRING_APPLICATION_JSON", os.environ)

    def test_wire_hash_vectors_match_owner_examples(self):
        self.assertEqual(25, hash_value("outcome", "worker-a", "fixed-salt", "+8613800000001") % 1000)
        self.assertEqual(4067, 2000 + hash_value("delay", "worker-a", "fixed-salt", "+8613800000001") % 3001)
        self.assertEqual(917, hash_value("outcome", "worker-b", "fixed-salt", "+8613800000001") % 1000)

    def test_baseline_configuration_is_explicit_and_does_not_add_the_new_pool_field(self):
        with patch.dict(os.environ):
            os.environ.pop("SPRING_APPLICATION_JSON", None)
            with regression_window_configuration(baseline=True):
                actual = json.loads(os.environ["SPRING_APPLICATION_JSON"])
                self.assertEqual(1000, actual["xa.mass.worker-matching.groups.app-a-sim.assignment-window.max-assignments"])
                self.assertFalse(any("worker-pools" in key for key in actual))
            self.assertNotIn("SPRING_APPLICATION_JSON", os.environ)

    def test_benchmark_pins_its_window_without_changing_functional_fixture_defaults(self):
        with patch.dict(os.environ, {"XA_MASS_APP_CHECKS_SEED": "file:///original.json"}):
            with regression_window_configuration(window_millis=60000):
                _, configuration = self.read_seed()
                self.assertEqual(60000, configuration["poolGroups"]["app-a-sim"]["windowMillis"])
            self.assertEqual("file:///original.json", os.environ["XA_MASS_APP_CHECKS_SEED"])

    def test_task_fixture_uses_distinct_valid_numbers_and_no_worker_hint(self):
        case = request("mixed", "app-b", 30, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50])
        self.assertEqual(16, len(set(case["numbers"])))
        self.assertTrue(all(number.startswith("+86") for number in case["numbers"]))
        self.assertNotIn("workerId", case)


if __name__ == "__main__":
    unittest.main()
