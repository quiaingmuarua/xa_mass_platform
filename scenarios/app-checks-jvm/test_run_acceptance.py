import unittest
from run_acceptance import hash_value, request


class AppCheckProofTest(unittest.TestCase):
    def test_wire_hash_vectors_match_owner_examples(self):
        self.assertEqual(25, hash_value("outcome", "worker-a", "fixed-salt", "+8613800000001") % 1000)
        self.assertEqual(4067, 2000 + hash_value("delay", "worker-a", "fixed-salt", "+8613800000001") % 3001)
        self.assertEqual(917, hash_value("outcome", "worker-b", "fixed-salt", "+8613800000001") % 1000)

    def test_task_fixture_uses_distinct_valid_numbers_and_no_worker_hint(self):
        case = request("mixed", "app-b", 30, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50])
        self.assertEqual(16, len(set(case["numbers"])))
        self.assertTrue(all(number.startswith("+86") for number in case["numbers"]))
        self.assertNotIn("workerId", case)


if __name__ == "__main__":
    unittest.main()
