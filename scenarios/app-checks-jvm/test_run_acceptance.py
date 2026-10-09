import unittest
import json
import os
from unittest.mock import patch
from run_acceptance import hash_value, request, regression_window_configuration


class AppCheckProofTest(unittest.TestCase):
    def test_window_fixture_keeps_other_settings_and_restores_environment_on_failure(self):
        original = json.dumps({"some.setting": "retained"})
        with patch.dict(os.environ, {"SPRING_APPLICATION_JSON": original}):
            with self.assertRaises(RuntimeError):
                with regression_window_configuration():
                    actual = json.loads(os.environ["SPRING_APPLICATION_JSON"])
                    self.assertEqual("retained", actual["some.setting"])
                    for group in ("app-a-sim", "app-b-sim"):
                        self.assertEqual(1000, actual[f"xa.mass.worker-pools.assignment-window.groups.{group}.max-count"])
                    self.assertFalse(any("window-millis" in name for name in actual))
                    raise RuntimeError("scenario failed")
            self.assertEqual(original, os.environ["SPRING_APPLICATION_JSON"])
        with patch.dict(os.environ):
            os.environ.pop("SPRING_APPLICATION_JSON", None)
            with regression_window_configuration():
                self.assertIn("SPRING_APPLICATION_JSON", os.environ)
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
        key = "xa.mass.worker-pools.assignment-window.groups.app-a-sim.window-millis"
        with patch.dict(os.environ, {"SPRING_APPLICATION_JSON": json.dumps({key: 100})}):
            with regression_window_configuration(window_millis=60000):
                self.assertEqual(60000, json.loads(os.environ["SPRING_APPLICATION_JSON"])[key])
            self.assertEqual(100, json.loads(os.environ["SPRING_APPLICATION_JSON"])[key])

    def test_task_fixture_uses_distinct_valid_numbers_and_no_worker_hint(self):
        case = request("mixed", "app-b", 30, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50])
        self.assertEqual(16, len(set(case["numbers"])))
        self.assertTrue(all(number.startswith("+86") for number in case["numbers"]))
        self.assertNotIn("workerId", case)


if __name__ == "__main__":
    unittest.main()
