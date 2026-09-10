import unittest
from types import SimpleNamespace
from unittest.mock import patch

from run_acceptance import compare


class AcceptanceOracleTest(unittest.TestCase):
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
