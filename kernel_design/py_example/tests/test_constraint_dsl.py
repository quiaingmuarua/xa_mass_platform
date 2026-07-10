from __future__ import annotations

import unittest

from kernel_design.py_example.constraint_dsl import (
    ConstraintDsl,
    UNRESOLVED_VALUE,
)


class ConstraintDslTest(unittest.TestCase):
    def test_compile_match_rules_validates_copies_and_freezes_document(self) -> None:
        accepted = ["gpu", "cpu"]
        document = {
            "resource.kind": {"$in": accepted},
            "resource.capacity": {"$gte": 2},
        }

        rules = ConstraintDsl.compile_match_rules(document)
        accepted.append("other")
        document["resource.capacity"]["$gte"] = 10

        self.assertEqual(rules["resource.kind"]["$in"], ("gpu", "cpu"))
        self.assertEqual(rules["resource.capacity"]["$gte"], 2)
        with self.assertRaises(TypeError):
            rules["resource.kind"] = {"$eq": "gpu"}  # type: ignore[index]

    def test_compile_match_rules_rejects_invalid_documents(self) -> None:
        invalid_documents = [
            None,
            {"resource.": {"$eq": "gpu"}},
            {"resource.kind": {"$regex": "gpu.*"}},
            {"resource.kind": {"$in": "gpu"}},
            {"resource.kind": {"$exists": "yes"}},
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    ConstraintDsl.compile_match_rules(document)

    def test_evaluate_match_rules_splits_only_the_first_dot(self) -> None:
        context = {
            "resource": {
                "identity.id": "r-1",
                "traits.region": "east",
                "traits.capacity": 4,
            },
            "dynamic": {"battery.level": 87},
        }
        rules = ConstraintDsl.compile_match_rules(
            {
                "resource.identity.id": {"$eq": "r-1"},
                "resource.traits.region": {"$in": ["east", "west"]},
                "resource.traits.capacity": {"$gte": 2},
                "resource.traits.retired": {"$exists": False},
                "dynamic.battery.level": {"$eq": 87},
            }
        )

        self.assertTrue(ConstraintDsl.evaluate_match_rules(context, rules))
        self.assertFalse(
            ConstraintDsl.evaluate_match_rules(
                context,
                ConstraintDsl.compile_match_rules(
                    {"resource.traits.capacity": {"$gte": 10}}
                ),
            )
        )

    def test_unresolved_value_fails_closed(self) -> None:
        self.assertFalse(
            ConstraintDsl.evaluate_match_rules(
                {"external": {"value": UNRESOLVED_VALUE}},
                ConstraintDsl.compile_match_rules(
                    {"external.value": {"$exists": False}}
                ),
            )
        )


if __name__ == "__main__":
    unittest.main()
