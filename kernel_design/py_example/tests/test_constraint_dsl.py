from __future__ import annotations

import unittest

from kernel_design.py_example.constraint_dsl import (
    UNRESOLVED_VALUE,
    WorkerConstraintQuery,
    matches_mapping,
)


class ConstraintDslTest(unittest.TestCase):
    def test_worker_query_compiles_declared_dynamic_dependencies(self) -> None:
        query = WorkerConstraintQuery(
            {
                "acquire_fields": ["dynamic.battery"],
                "match_rules": {
                    "workerId": {"$in": ["worker-1", "worker-2"]},
                    "system.tier": {"$eq": "premium"},
                    "static.runtime": {"$in": ["python", "java"]},
                    "dynamic.battery": {"$gte": 20},
                },
            }
        )

        self.assertEqual(query.acquire_fields, ("dynamic.battery",))
        self.assertEqual(query.worker_id_filter(), frozenset({"worker-1", "worker-2"}))
        self.assertEqual(query.system_fields, {"system.tier": "tier"})
        self.assertEqual(query.static_fields, {"static.runtime": "runtime"})
        self.assertEqual(query.dynamic_fields, {"dynamic.battery": "battery"})
        self.assertEqual(
            set(query.metadata_rules),
            {"workerId", "system.tier", "static.runtime"},
        )
        self.assertEqual(set(query.dynamic_rules), {"dynamic.battery"})

    def test_worker_query_requires_exact_dynamic_dependency_declaration(self) -> None:
        invalid_documents = [
            {
                "acquire_fields": [],
                "match_rules": {"dynamic.battery": {"$gte": 20}},
            },
            {
                "acquire_fields": ["dynamic.battery"],
                "match_rules": {},
            },
            {
                "acquire_fields": ["dynamic.battery", "dynamic.battery"],
                "match_rules": {"dynamic.battery": {"$gte": 20}},
            },
            {
                "acquire_fields": ["static.runtime"],
                "match_rules": {"static.runtime": {"$eq": "python"}},
            },
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    WorkerConstraintQuery(document)

    def test_worker_query_rejects_invalid_shapes_during_construction(self) -> None:
        invalid_documents = [
            {"workerId": {"$eq": "worker-1"}},
            {"acquire_fields": [], "match_rules": {}, "unknown": True},
            {"acquire_fields": "dynamic.battery", "match_rules": {}},
            {"acquire_fields": [], "match_rules": {"$or": []}},
            {
                "acquire_fields": [],
                "match_rules": {"system": {"tier": {"$eq": "premium"}}},
            },
            {
                "acquire_fields": [],
                "match_rules": {"workerGroupId": {"$eq": "image-workers"}},
            },
            {
                "acquire_fields": [],
                "match_rules": {"workerId": {"$gt": "worker-1"}},
            },
            {
                "acquire_fields": [],
                "match_rules": {"workerId": {"$in": "worker-1"}},
            },
            {
                "acquire_fields": [],
                "match_rules": {"system.": {"$eq": "premium"}},
            },
            {
                "acquire_fields": [],
                "match_rules": {"static.runtime": {"$regex": "py.*"}},
            },
            {
                "acquire_fields": ["dynamic.battery"],
                "match_rules": {"dynamic.battery": {"$exists": "yes"}},
            },
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    WorkerConstraintQuery(document)

    def test_worker_query_copies_and_freezes_validated_input(self) -> None:
        allowed_worker_ids = ["worker-1"]
        acquire_fields = ["dynamic.battery"]
        match_rules = {
            "workerId": {"$in": allowed_worker_ids},
            "system.tier": {"$eq": "premium"},
            "dynamic.battery": {"$gte": 20},
        }
        query = WorkerConstraintQuery(
            {
                "acquire_fields": acquire_fields,
                "match_rules": match_rules,
            }
        )

        match_rules["system.tier"]["$eq"] = "standard"
        allowed_worker_ids.append("worker-2")
        acquire_fields.append("dynamic.network")
        self.assertEqual(query.acquire_fields, ("dynamic.battery",))
        self.assertEqual(query.match_rules["system.tier"]["$eq"], "premium")
        self.assertEqual(query.worker_id_filter(), frozenset({"worker-1"}))
        self.assertEqual(query.match_rules["workerId"]["$in"], ("worker-1",))

        with self.assertRaises(TypeError):
            query.match_rules["system.tier"] = {"$eq": "standard"}  # type: ignore[index]
        with self.assertRaises(TypeError):
            query.match_rules["system.tier"]["$eq"] = "standard"  # type: ignore[index]

    def test_mapping_evaluator_matches_only_declared_fields(self) -> None:
        values = {
            "system.tier": "premium",
            "static.region": "us-east",
            "static.gpuCount": 4,
            "unused": "ignored",
        }
        constraints = {
            "system.tier": {"$equal": "premium"},
            "static.region": {"$in": ["us-east", "us-west"]},
            "static.gpuCount": {"$gte": 2},
            "static.deprecated": {"$exists": False},
        }

        self.assertTrue(matches_mapping(values, constraints))
        self.assertFalse(
            matches_mapping(values, {"static.gpuCount": {"$gte": "many"}})
        )

    def test_unresolved_value_fails_closed(self) -> None:
        self.assertFalse(
            matches_mapping(
                {"dynamic.battery": UNRESOLVED_VALUE},
                {"dynamic.battery": {"$exists": False}},
            )
        )


if __name__ == "__main__":
    unittest.main()
