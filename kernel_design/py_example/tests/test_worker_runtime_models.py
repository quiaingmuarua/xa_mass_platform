from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    ConstraintFieldResolution,
    WorkerCandidateMatcher,
    WorkerConstraintQuery,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
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
                "system_metadata",
                "static_attributes",
                "dynamic_attribute_names",
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

    def test_worker_constraint_query_evaluates_stable_operator_semantics(self) -> None:
        query = WorkerConstraintQuery(
            {
                "worker.id": {"$eq": "worker-1"},
                "system.tier": {"$equal": "premium"},
                "static.gpuCount": {"$gte": 2},
                "static.region": {"$in": ["us-east", "us-west"]},
                "dynamic.battery": {"$gt": 20},
                "static.deprecated": {"$exists": False},
            }
        )
        values = {
            "worker.id": ConstraintFieldResolution.present_value("worker-1"),
            "system.tier": ConstraintFieldResolution.present_value("premium"),
            "static.gpuCount": ConstraintFieldResolution.present_value(4),
            "static.region": ConstraintFieldResolution.present_value("us-east"),
            "dynamic.battery": ConstraintFieldResolution.present_value(87),
            "static.deprecated": ConstraintFieldResolution.missing(),
        }

        self.assertTrue(query.matches(lambda field_name: values[field_name]))

    def test_worker_constraint_query_fails_unresolved_or_non_comparable_fields(self) -> None:
        unresolved = WorkerConstraintQuery({"dynamic.battery": {"$exists": False}})
        non_comparable = WorkerConstraintQuery({"static.gpuCount": {"$gte": 2}})

        self.assertFalse(
            unresolved.matches(lambda _: ConstraintFieldResolution.unresolved())
        )
        self.assertFalse(
            non_comparable.matches(
                lambda _: ConstraintFieldResolution.present_value("many")
            )
        )

    def test_worker_runtime_interfaces_expose_narrow_owner_surfaces(self) -> None:
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "refresh_worker_static_attributes",
                "register_worker_descriptor",
                "register_worker_group_descriptor",
                "update_worker_system_metadata",
            },
        )
        self.assertEqual(
            WorkerDynamicAttributeRuntime.__abstractmethods__,
            {"update_worker_dynamic_attributes"},
        )
        self.assertFalse(inspect.isabstract(WorkerCandidateMatcher))
        self.assertFalse(hasattr(py_example, "WorkerAdmission"))
        self.assertFalse(hasattr(py_example, "WorkerAdmissionResult"))
        self.assertFalse(hasattr(py_example, "WorkerAdmissionRuntime"))
        self.assertFalse(hasattr(py_example, "WorkerMatchResult"))
        self.assertFalse(hasattr(py_example, "WorkerReservation"))
        self.assertFalse(hasattr(py_example, "WorkerReservationHandle"))
        self.assertFalse(hasattr(py_example, "WorkerReservationResult"))
        self.assertFalse(hasattr(py_example, "WorkerReservationRuntime"))
        self.assertFalse(hasattr(py_example, "WorkerValidationResult"))

    def test_worker_candidate_matcher_batches_constraint_queries(self) -> None:
        match_params = set(
            inspect.signature(WorkerCandidateMatcher.match_worker_candidates).parameters
        )

        self.assertEqual(
            match_params,
            {"self", "worker_group_id", "worker_ids", "candidate_constraints"},
        )

    def test_dynamic_attribute_update_ingress_is_catalog_owned(self) -> None:
        update_params = set(
            inspect.signature(
                WorkerDynamicAttributeRuntime.update_worker_dynamic_attributes
            ).parameters
        )

        self.assertEqual(
            update_params,
            {"self", "worker_id", "updates", "observed_at_millis"},
        )
        self.assertTrue(hasattr(py_example, "DynamicAttributePayload"))

    def test_dynamic_attribute_value_lives_behind_function_table(self) -> None:
        descriptor = WorkerDescriptor(
            worker_id="worker-1",
            worker_group_id="image-workers",
            system_metadata={},
            static_attributes={"runtimeVersion": "1.0.0"},
            dynamic_attribute_names=frozenset({"battery"}),
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
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))


if __name__ == "__main__":
    unittest.main()
