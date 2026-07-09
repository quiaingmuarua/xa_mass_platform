from __future__ import annotations

import unittest
from dataclasses import fields

from kernel_design.py_example import (
    WorkerAdmissionRuntime,
    WorkerConstraintQuery,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerResourceCatalog,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)
from kernel_design.py_example.kernel.worker_runtime import DynamicAttributeReadResult


class WorkerRuntimeModelTest(unittest.TestCase):
    def test_worker_descriptor_first_layer_shape_has_no_version_field(self) -> None:
        field_names = {field.name for field in fields(WorkerDescriptor)}

        self.assertEqual(
            field_names,
            {
                "worker_id",
                "worker_group_id",
                "system_attributes",
                "static_attributes",
                "dynamic_attributes",
            },
        )

    def test_worker_group_event_codes_are_group_promise_metadata(self) -> None:
        descriptor = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image"},
            event_codes=frozenset({"resize", "thumbnail"}),
        )

        self.assertEqual(descriptor.worker_group_id, "image-workers")
        self.assertIn("resize", descriptor.event_codes)

    def test_worker_constraint_query_is_identity_and_attribute_predicates(self) -> None:
        query = WorkerConstraintQuery(
            {
                "worker.id": {"$in": ["worker-1", "worker-2"]},
                "system.tier": {"$eq": "premium"},
                "static.runtime": {"$in": ["python", "java"]},
                "dynamic.battery": {"$gte": 20},
            }
        )

        self.assertEqual(query.worker_id_filter(), frozenset({"worker-1", "worker-2"}))
        self.assertNotIn("worker.group_id", query.predicates)
        self.assertNotIn("event_code", query.predicates)

    def test_worker_constraint_query_rejects_broad_policy_shapes(self) -> None:
        with self.assertRaises(ValueError):
            WorkerConstraintQuery({"$or": [{"static.runtime": {"$eq": "python"}}]})
        with self.assertRaises(ValueError):
            WorkerConstraintQuery({"worker.group_id": {"$eq": "image-workers"}})
        with self.assertRaises(ValueError):
            WorkerConstraintQuery({"worker.id": {"$gt": "worker-1"}})

    def test_worker_runtime_interfaces_expose_only_catalog_and_admission(self) -> None:
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "refresh_worker_static_attributes",
                "register_worker_descriptor",
                "register_worker_group_descriptor",
                "update_worker_system_attributes",
            },
        )
        self.assertEqual(
            WorkerAdmissionRuntime.__abstractmethods__,
            {
                "admit_worker",
                "release_admission",
                "revalidate_admission",
                "validate_worker_candidates",
            },
        )
        self.assertFalse(hasattr(WorkerAdmissionRuntime, "register_worker_descriptor"))
        self.assertFalse(hasattr(WorkerAdmissionRuntime, "update_worker_dynamic_attribute"))
        self.assertFalse(hasattr(WorkerAdmissionRuntime, "read_worker_dynamic_attribute"))

    def test_dynamic_attribute_value_lives_behind_function_table(self) -> None:
        descriptor = WorkerDescriptor(
            worker_id="worker-1",
            worker_group_id="image-workers",
            system_attributes={},
            static_attributes={"runtimeVersion": "1.0.0"},
            dynamic_attributes=frozenset({"battery"}),
        )
        values: dict[str, tuple[object, int]] = {}

        def update_battery(
            worker_id: str,
            payload: object,
            observed_at_millis: int,
        ) -> WorkerRuntimeResult:
            values[worker_id] = (payload, observed_at_millis)
            return WorkerRuntimeResult(status=WorkerRuntimeStatus.OK)

        def query_battery(worker_id: str) -> DynamicAttributeReadResult:
            value = values.get(worker_id)
            if value is None:
                return DynamicAttributeReadResult(status=WorkerRuntimeStatus.NOT_FOUND)
            payload, observed_at_millis = value
            return DynamicAttributeReadResult(
                status=WorkerRuntimeStatus.OK,
                value=payload,
                observed_at_millis=observed_at_millis,
            )

        update_dynamic_attributes_dict = {"battery": update_battery}
        query_dynamic_attributes_dict = {"battery": query_battery}

        result = update_dynamic_attributes_dict["battery"](
            descriptor.worker_id,
            87,
            10_000,
        )
        read = query_dynamic_attributes_dict["battery"](descriptor.worker_id)

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.value, 87)
        self.assertEqual(descriptor.dynamic_attributes, frozenset({"battery"}))


if __name__ == "__main__":
    unittest.main()
