import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

import run_acceptance
from run_acceptance import compare


class AcceptanceOracleTest(unittest.TestCase):
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
def http(*args):
    return {"packagedHttp": True}
def all_pages(*args):
    return []
class Preview:
    def __init__(self, counts, port, root, output, products, sandbox_root):
        self.artifacts, self.peaks = {}, {}
        Path(root, "launch.json").write_text(json.dumps({"counts": counts, "products": products, "sandboxRoot": str(sandbox_root)}))
    def __enter__(self):
        return self
    def __exit__(self, *_):
        pass
''', encoding="utf-8")
            original_http, original_pages = run_acceptance.http, run_acceptance.all_pages
            self.addCleanup(setattr, run_acceptance, "http", original_http)
            self.addCleanup(setattr, run_acceptance, "all_pages", original_pages)
            def check_packaged_http(_):
                self.assertEqual({"packagedHttp": True}, run_acceptance.http())
                return {"passed": True}
            with patch("sys.argv", ["run_acceptance.py", "--root", str(root), "--output", str(root / "proof")]), \
                 patch("run_acceptance.functional", side_effect=check_packaged_http), patch("builtins.print"):
                with self.assertRaises(SystemExit) as stopped:
                    run_acceptance.main()
            self.assertEqual(0, stopped.exception.code)
            launch = json.loads((root / "launch.json").read_text())
            self.assertEqual([1, 1, 1], launch["counts"])
            self.assertEqual("sms", launch["products"])
            inventory = Path(launch["sandboxRoot"])
            self.assertEqual(root / "proof/private", inventory.parents[2])
            self.assertTrue(inventory.parents[1].name.startswith("inventory-"))
            self.assertEqual(Path("data/scenario-workers"), inventory.relative_to(inventory.parents[1]))
            summary = json.loads((root / "proof/summary.json").read_text())
            self.assertEqual(64, len(summary["launcherSha256"]))

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
        host = [{"listenerId": "a", "smsId": "sms-a", "status": "RECEIVED"},
                {"listenerId": "b", "smsId": "sms-b", "status": "RECEIVED"}]
        correct = [{"id": "a", "sms": {"smsId": "sms-a"}, "status": "RECEIVED"},
                   {"id": "b", "sms": {"smsId": "sms-b"}, "status": "RECEIVED"}]
        swapped = [{"id": "a", "sms": {"smsId": "sms-b"}, "status": "RECEIVED"},
                   {"id": "b", "sms": {"smsId": "sms-a"}, "status": "RECEIVED"}]
        run = SimpleNamespace(host="host", url="product")
        with patch("run_acceptance.all_records", side_effect=[host, correct]):
            valid = compare(run)
        self.assertEqual(1, valid["smsObservationRate"])
        self.assertEqual(0, valid["falseSuccesses"])
        self.assertEqual(64, len(valid["matchedIdentityDigest"]))
        with patch("run_acceptance.all_records", side_effect=[host, swapped]):
            invalid = compare(run)
        self.assertEqual(2, invalid["falseSuccesses"])
        self.assertEqual(2, invalid["missingSmsObservations"])
        self.assertEqual(0, invalid["smsObservationRate"])

    def test_unconfirmed_cannot_be_counted_as_observed_sms(self):
        with patch("run_acceptance.all_records", side_effect=[
            [{"listenerId": "a", "smsId": "one", "status": "RECEIVED"}],
            [{"id": "a", "status": "UNCONFIRMED"}],
        ]):
            result = compare(SimpleNamespace(host="host", url="product"))
        self.assertEqual(1, result["missingSmsObservations"])
        self.assertEqual(1, result["stateMismatches"])
        self.assertEqual(0, result["observedSms"])


if __name__ == "__main__":
    unittest.main()
