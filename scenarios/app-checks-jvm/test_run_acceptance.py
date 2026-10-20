import unittest
import io
import tempfile
from contextlib import redirect_stdout, redirect_stderr
from types import SimpleNamespace
import run_acceptance as acceptance
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import url2pathname
from unittest.mock import patch
from run_acceptance import caller_headers, hash_value, request, creation_input, regression_window_configuration


class AppCheckProofTest(unittest.TestCase):
    def test_text_creation_does_not_invent_phone_options(self):
        self.assertEqual({"requestId": "demo", "checkId": "uint32-prime"},
                         creation_input({"userId": "1000", "requestId": "demo", "checkId": "uint32-prime", "numbers": ["17"]}))

    def read_seed(self, overrides):
        path = Path(url2pathname(urlparse(overrides["xa.mass.scenarios.app-checks.configuration.seed"]).path))
        return path, json.loads(path.read_text(encoding="utf-8"))

    def test_sequential_configuration_fixtures_do_not_rebind_ports_from_a_closed_run(self):
        retained_ports = set()
        runs = []

        class Preview:
            def __init__(self, count, port, **settings):
                self.port = port
                self.artifacts = {}
                self.settings = settings
                runs.append(self)

            def __enter__(self):
                ports = {self.port, self.port + 3, self.port + 4}
                if retained_ports & ports:
                    raise OSError("previous TCP connection still occupies this port")
                retained_ports.update(ports)
                return self

            def __exit__(self, *_):
                # JVM exit need not immediately release every TCP bind constraint.
                pass

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "run_preview.py").write_text("# fixture launcher\n", encoding="utf-8")
            preview = SimpleNamespace(Preview=Preview, http=object())
            spec = SimpleNamespace(loader=SimpleNamespace(exec_module=lambda _: None))
            with patch("sys.argv", ["run_acceptance.py", "--root", str(root), "--output", str(root / "proof"), "--port", "18620"]), \
                    patch.object(acceptance.importlib.util, "spec_from_file_location", return_value=spec), \
                    patch.object(acceptance.importlib.util, "module_from_spec", return_value=preview), \
                    patch.object(acceptance, "functional", side_effect=lambda *_: {"passed": True}) as workload, \
                    redirect_stdout(io.StringIO()):
                acceptance.main()
            evidence = json.loads((root / "proof/summary.json").read_text(encoding="utf-8"))
            self.assertTrue(evidence["passed"])
            self.assertEqual([18620, 18630], [run.port for run in runs])
            self.assertEqual(6, len(retained_ports))
            self.assertEqual([18620, 18630], [fixture["basePort"] for fixture in evidence["fixtures"]])
            self.assertNotEqual(runs[0].settings["sandbox_root"], runs[1].settings["sandbox_root"])
            self.assertEqual([4, 4], [len(call.args[2]) for call in workload.call_args_list])

    def test_functional_port_range_is_rejected_before_loading_or_starting_a_fixture(self):
        for port in (0, 65522):
            with patch("sys.argv", ["run_acceptance.py", "--port", str(port)]), \
                    patch.object(acceptance.importlib.util, "spec_from_file_location") as load, \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                acceptance.main()
            load.assert_not_called()

    def test_execution_variants_are_seed_parameters_not_create_overrides(self):
        parameters = {"ranges": {"registered": [0,1000], "unregistered": [1000,1000], "failed": [1000,1000]}, "delayMs": [0,0]}
        with regression_window_configuration(parameters_by_check={"app-a-registration": parameters}) as overrides:
            _, document = self.read_seed(overrides)
            self.assertEqual(document["checks"]["app-a-registration"]["parameters"], parameters)
            self.assertNotIn("parameters", document["checks"]["app-b-registration"])
            for check in ("original-carrier", "md5-prefix"):
                self.assertEqual(10, document["checks"][check]["execution"]["seedsPerItem"])
                self.assertTrue(document["checks"][check]["execution"]["eventCode"].endswith(".batch"))
            for check in ("app-a-registration", "app-b-registration", "uint32-prime"):
                self.assertEqual(1, document["checks"][check]["execution"]["seedsPerItem"])

    def test_window_fixture_keeps_other_settings_and_restores_environment_on_failure(self):
        original = json.dumps({"some.setting": "retained"})
        with patch.dict(os.environ, {"SPRING_APPLICATION_JSON": original}):
            with self.assertRaises(RuntimeError):
                with regression_window_configuration() as overrides:
                    actual = overrides
                    self.assertNotIn("some.setting", actual)
                    path, configuration = self.read_seed(overrides)
                    self.assertNotIn("poolGroups", configuration)
                    self.assertEqual(1000, actual["xa.mass.worker-assembly.fixed-window.pools.assignment-window.max-count"])
                    raise RuntimeError("scenario failed")
            self.assertEqual(original, os.environ["SPRING_APPLICATION_JSON"])
            self.assertFalse(path.exists())
        with patch.dict(os.environ):
            os.environ.pop("SPRING_APPLICATION_JSON", None)
            with regression_window_configuration() as overrides:
                self.assertIn("xa.mass.scenarios.app-checks.configuration.seed", overrides)
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
            with regression_window_configuration(window_millis=60000) as overrides:
                _, configuration = self.read_seed(overrides)
                self.assertNotIn("poolGroups", configuration)
                self.assertEqual(60000, overrides["xa.mass.worker-assembly.fixed-window.pools.assignment-window.window-millis"])
            self.assertEqual("file:///original.json", os.environ["XA_MASS_APP_CHECKS_SEED"])

    def test_task_fixture_uses_distinct_valid_numbers_and_no_worker_hint(self):
        case = request("mixed", "app-b", 30, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50])
        self.assertEqual(16, len(set(case["numbers"])))
        self.assertTrue(all(number.startswith("+86") for number in case["numbers"]))
        self.assertNotIn("workerId", case)
        self.assertEqual({"X-XA-Dev-User": "MTAwMA", "X-XA-Dev-Role": "admin"}, caller_headers(case["userId"], "admin"))
        self.assertNotIn("userId", creation_input(case))
        self.assertNotIn("numbers", creation_input(case))
        self.assertEqual(set(creation_input(case)), {"requestId", "checkId", "country"})
        self.assertEqual("app-b-registration", creation_input(case)["checkId"])


if __name__ == "__main__":
    unittest.main()
