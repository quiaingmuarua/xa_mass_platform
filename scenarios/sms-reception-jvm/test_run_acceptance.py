import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

import run_acceptance
from run_acceptance import compare


class AcceptanceOracleTest(unittest.TestCase):
    def test_server_restart_reuses_the_previews_isolated_configuration_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = run_acceptance.preview.Preview(1, 18400, root=root, output=root,
                    sandbox_root=root / "data/scenario-workers", app_count=0)
            run.server_jar, run.frontend = root / "preview.jar", root / "frontend"
            process = Mock(args=run.server_arguments())
            run.processes = {"server": process}
            run.launch, run.wait_for, run.connected = Mock(), Mock(), Mock()
            (root / "server.log").write_text("first startup", encoding="utf-8")
            with patch.dict("os.environ", {"XA_MASS_APP_CHECKS_CONFIG_PATH": "unrelated-host-configuration"}):
                run_acceptance.restart_server(run)
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=10)
            name, arguments, environment = run.launch.call_args.args
            self.assertEqual("server", name)
            self.assertEqual(process.args, arguments)
            self.assertIn("--xa.mass.scenarios.app-checks.configuration.path=" + str(run.app_checks_config), arguments)
            self.assertIn("--xa.mass.task-retention.directory=" + str(run.task_archives), arguments)
            self.assertIn("--xa.mass.redis.scope=" + run.scope, arguments)
            self.assertIn("--server.port=18400", arguments)
            self.assertEqual(run.redis_url, environment["XA_MASS_LOCAL_REDIS_URL"])
            self.assertNotIn("XA_MASS_APP_CHECKS_CONFIG_PATH", environment)
            self.assertEqual("first startup", (root / "server-before-restart.log").read_text(encoding="utf-8"))

    def test_complete_initial_responses_include_tail_and_all_countries(self):
        result = run_acceptance.initial_response_summary({"CN:WAITING": 350, "CN:NOT_OBSERVED": 50,
                                                         "US:RECEIVED": 80, "GB:WAITING": 20})
        self.assertEqual(500, result["total"])
        self.assertEqual(.1, result["notObservedRate"])
        self.assertEqual(.125, result["byCountry"]["CN"]["notObservedRate"])
        self.assertEqual(0, result["byCountry"]["US"]["notObserved"])
        self.assertEqual(0, run_acceptance.initial_response_summary({})["total"])

    def test_explicit_old_phone_reaches_target_worker_owner_without_client_side_rejection(self):
        target = {"workerGroupId": "demo-sim", "replicaKey": "workers-000.jsonl:1"}
        with patch("run_acceptance.http", return_value={"status": "IGNORED"}) as call, \
                patch("run_acceptance.all_pages", side_effect=AssertionError("Do not reroute by phone")):
            run_acceptance.inject(SimpleNamespace(host="host"), "old-phone", "text", "id", worker=target)
        call.assert_called_once_with("host", "/lab/v1/workers/demo-sim/workers-000.jsonl%3A1:inputs",
                                     {"eventName": "sms.receive", "payload": {"phone": "old-phone", "text": "text", "smsId": "id"}})

    def test_extracted_preview_owns_sms_launch_and_http(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "run_preview.py").write_text('''
from pathlib import Path
import json
def http(*args, **kwargs):
    return {"packagedHttp": True, "tasks": [], "messages": 0}
def all_pages(*args):
    return []
class Preview:
    def __init__(self, count, port, root, output, sandbox_root, app_count):
        self.artifacts, self.peaks = {}, {}
        self.url, self.host = "backend", "host"
        Path(root, "launch.json").write_text(json.dumps({"count": count, "appCount": app_count, "scenarios": ["sms", "messages", "app-checks"], "sandboxRoot": str(sandbox_root)}))
    def __enter__(self):
        return self
    def __exit__(self, *_):
        pass
''', encoding="utf-8")
            original_http, original_pages = run_acceptance.http, run_acceptance.all_pages
            self.addCleanup(setattr, run_acceptance, "http", original_http)
            self.addCleanup(setattr, run_acceptance, "all_pages", original_pages)
            def check_packaged_http(_):
                self.assertTrue(run_acceptance.http()["packagedHttp"])
                return {"passed": True}
            with patch("sys.argv", ["run_acceptance.py", "--root", str(root), "--output", str(root / "proof")]), \
                 patch("run_acceptance.functional", side_effect=check_packaged_http), patch("builtins.print"):
                with self.assertRaises(SystemExit) as stopped:
                    run_acceptance.main()
            self.assertEqual(0, stopped.exception.code)
            launch = json.loads((root / "launch.json").read_text())
            self.assertEqual(3, launch["count"])
            self.assertEqual(["sms", "messages", "app-checks"], launch["scenarios"])
            self.assertEqual(0, launch["appCount"])
            inventory = Path(launch["sandboxRoot"])
            self.assertEqual(root / "proof/private", inventory.parents[2])
            self.assertTrue(inventory.parents[1].name.startswith("inventory-"))
            self.assertEqual(Path("data/scenario-workers"), inventory.relative_to(inventory.parents[1]))
            properties = [json.loads(line)["workerProperties"] for line in
                          (inventory / "demo-sim/workers-000.jsonl").read_text().splitlines()]
            self.assertEqual(["CN", "US", "GB"], [item["country"] for item in properties])
            self.assertTrue(all("messaging.enabled" not in item for item in properties))
            summary = json.loads((root / "proof/summary.json").read_text())
            self.assertEqual(64, len(summary["launcherSha256"]))

    def test_sms_only_workload_rejects_unsolicited_messages_records(self):
        run = SimpleNamespace(url="backend", host="host")
        with patch("run_acceptance.http", side_effect=[{"tasks": []}, {"messages": 0}]):
            self.assertEqual(0, run_acceptance.require_idle_messages(run)["hostMessages"])
        for backend, host in (({"tasks": [{"managed": False, "sendTotal": 0}]}, {"messages": 0}),
                              ({"tasks": [{"managed": False, "sendTotal": 1}]}, {"messages": 0}),
                              ({"tasks": []}, {"messages": 1})):
            with patch("run_acceptance.http", side_effect=[backend, host]):
                with self.assertRaisesRegex(AssertionError, "Messages business records"):
                    run_acceptance.require_idle_messages(run)

    def test_prepare_audit_counts_both_paths_and_rejects_incomplete_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime-http.log"
            path.write_text("POST /api/v1/worker-groups/demo-sim/workers:prepare-batch 200\n"
                            "POST /lab/v1/workers/demo-sim/record:inputs 200\n"
                            "POST /api/v1/worker-groups/demo-sim/workers:prepare 503\n", encoding="utf-8")
            self.assertEqual({"prepare": 1, "prepare-batch": 1}, run_acceptance.prepare_counts(path))
            path.write_text("POST /api/v1/worker-groups/demo-sim/workers:prepare", encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "Incomplete"):
                run_acceptance.prepare_counts(path)

    def test_missing_packaged_launcher_has_no_checkout_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                run_acceptance.load_preview(Path(directory))

    def test_matching_counts_alone_do_not_hide_wrong_order_association(self):
        host = [{"messageId": "a", "smsId": "sms-a", "status": "RECEIVED"},
                {"messageId": "b", "smsId": "sms-b", "status": "RECEIVED"}]
        correct = [{"messageId": "a", "sms": {"smsId": "sms-a"}, "status": "RECEIVED"},
                   {"messageId": "b", "sms": {"smsId": "sms-b"}, "status": "RECEIVED"}]
        swapped = [{"messageId": "a", "sms": {"smsId": "sms-b"}, "status": "RECEIVED"},
                   {"messageId": "b", "sms": {"smsId": "sms-a"}, "status": "RECEIVED"}]
        run = SimpleNamespace(host="host", url="product")
        with patch("run_acceptance.all_records", return_value=host), patch("run_acceptance.query_records", return_value=correct):
            valid = compare(run)
        self.assertEqual(1, valid["smsObservationRate"])
        self.assertEqual(0, valid["falseSuccesses"])
        self.assertEqual(64, len(valid["matchedIdentityDigest"]))
        with patch("run_acceptance.all_records", return_value=host), patch("run_acceptance.query_records", return_value=swapped):
            invalid = compare(run)
        self.assertEqual(2, invalid["falseSuccesses"])
        self.assertEqual(2, invalid["missingSmsObservations"])
        self.assertEqual(0, invalid["smsObservationRate"])

    def test_unconfirmed_cannot_be_counted_as_observed_sms(self):
        with patch("run_acceptance.all_records", return_value=[{"messageId": "a", "smsId": "one", "status": "RECEIVED"}]), \
                patch("run_acceptance.query_records", return_value=[{"messageId": "a", "status": "NOT_OBSERVED"}]):
            result = compare(SimpleNamespace(host="host", url="product"))
        self.assertEqual(1, result["missingSmsObservations"])
        self.assertEqual(0, result["observedSms"])


if __name__ == "__main__":
    unittest.main()
