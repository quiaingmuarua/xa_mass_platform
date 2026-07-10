from __future__ import annotations

import unittest

from kernel_design.py_example.constraint_dsl import (
    UNRESOLVED_VALUE,
    WorkerConstraintQuery,
    matches_mapping,
)


class ConstraintDslTest(unittest.TestCase):
    def test_worker_query_precompiles_flat_field_categories(self) -> None:
        query = WorkerConstraintQuery(
            {
                "workerId": {"$in": ["worker-1", "worker-2"]},
                "system.tier": {"$eq": "premium"},
                "static.runtime": {"$in": ["python", "java"]},
                "dynamic.battery": {"$gte": 20},
            }
        )

        self.assertEqual(query.worker_id_filter(), frozenset({"worker-1", "worker-2"}))
        self.assertEqual(query.system_fields, {"system.tier": "tier"})
        self.assertEqual(query.static_fields, {"static.runtime": "runtime"})
        self.assertEqual(query.dynamic_fields, {"dynamic.battery": "battery"})

    def test_worker_query_rejects_invalid_shapes_during_construction(self) -> None:
        invalid_documents = [
            {"$or": []},
            {"system": {"tier": {"$eq": "premium"}}},
            {"workerGroupId": {"$eq": "image-workers"}},
            {"workerId": {"$gt": "worker-1"}},
            {"workerId": {"$in": "worker-1"}},
            {"system.": {"$eq": "premium"}},
            {"static.runtime": {"$regex": "py.*"}},
            {"dynamic.battery": {"$exists": "yes"}},
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    WorkerConstraintQuery(document)

    def test_worker_query_copies_and_freezes_validated_input(self) -> None:
        allowed_worker_ids = ["worker-1"]
        document = {
            "workerId": {"$in": allowed_worker_ids},
            "system.tier": {"$eq": "premium"},
        }
        query = WorkerConstraintQuery(document)

        document["system.tier"]["$eq"] = "standard"
        allowed_worker_ids.append("worker-2")
        self.assertEqual(query.predicates["system.tier"]["$eq"], "premium")
        self.assertEqual(query.worker_id_filter(), frozenset({"worker-1"}))
        self.assertEqual(query.predicates["workerId"]["$in"], ("worker-1",))

        with self.assertRaises(TypeError):
            query.predicates["system.tier"] = {"$eq": "standard"}  # type: ignore[index]
        with self.assertRaises(TypeError):
            query.predicates["system.tier"]["$eq"] = "standard"  # type: ignore[index]

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
