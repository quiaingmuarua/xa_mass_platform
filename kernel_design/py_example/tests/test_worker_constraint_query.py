from __future__ import annotations

import unittest

from kernel_design.py_example.kernel import WorkerConstraintQuery


class WorkerConstraintQueryTest(unittest.TestCase):
    def test_query_keeps_only_acquire_fields_and_match_rules(self) -> None:
        query = WorkerConstraintQuery(
            {
                "acquire_fields": ["dynamic.battery"],
                "match_rules": {
                    "workerId": {"$in": ["worker-1", "worker-2"]},
                    "static.runtime": {"$eq": "python"},
                    "dynamic.battery": {"$gte": 20},
                },
            }
        )

        self.assertEqual(query.acquire_fields, ("dynamic.battery",))
        self.assertEqual(
            tuple(query.match_rules),
            ("workerId", "static.runtime", "dynamic.battery"),
        )

    def test_query_requires_valid_declared_acquire_fields(self) -> None:
        invalid_documents = [
            {"workerId": {"$eq": "worker-1"}},
            {"acquire_fields": "dynamic.battery", "match_rules": {}},
            {
                "acquire_fields": ["dynamic.battery"],
                "match_rules": {},
            },
            {
                "acquire_fields": ["dynamic.battery", "dynamic.battery"],
                "match_rules": {"dynamic.battery": {"$gte": 20}},
            },
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    WorkerConstraintQuery(document)


if __name__ == "__main__":
    unittest.main()
