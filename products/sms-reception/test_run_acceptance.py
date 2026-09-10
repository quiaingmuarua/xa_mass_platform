import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

import run_acceptance
from run_acceptance import compare


class AcceptanceOracleTest(unittest.TestCase):
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
    def __init__(self, counts, port, root, output, products):
        self.artifacts, self.peaks = {}, {}
        Path(root, "launch.json").write_text(json.dumps({"counts": counts, "products": products}))
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
            self.assertEqual({"counts": [1, 1, 1], "products": "sms"},
                             json.loads((root / "launch.json").read_text()))
            summary = json.loads((root / "proof/summary.json").read_text())
            self.assertEqual(64, len(summary["launcherSha256"]))

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
