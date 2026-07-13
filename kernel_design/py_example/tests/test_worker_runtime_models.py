from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    WorkerCandidateConstraint,
    WorkerCandidateMatcher,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
    WorkerGroupDescriptor,
    WorkerResourceCatalog,
    WorkerRuntime,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)
from kernel_design.py_example.kernel.worker_runtime import DynamicAttributeReadResult


class WorkerRuntimeModelTest(unittest.TestCase):
    def test_worker_candidate_constraint_is_bounded_priority_dto(self) -> None:
        constraint = WorkerCandidateConstraint(
            priority=100,
            limit=2,
            match_rules={"dynamic.battery": {"$gte": 20}},
        )

        self.assertEqual(
            {field.name for field in fields(WorkerCandidateConstraint)},
            {"priority", "limit", "match_rules"},
        )
        self.assertEqual(constraint.priority, 100)
        self.assertEqual(constraint.limit, 2)

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

    def test_worker_runtime_interfaces_expose_narrow_owner_surfaces(self) -> None:
        self.assertEqual(
            WorkerRuntime.__abstractmethods__,
            {"register_worker_descriptor"},
        )
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "refresh_worker_static_attributes",
                "register_worker_group_descriptor",
                "update_worker_system_metadata",
            },
        )
        self.assertEqual(
            WorkerDynamicAttributeRuntime.__abstractmethods__,
            {
                "get_worker_dynamic_attribute_values",
                "update_worker_dynamic_attributes",
            },
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

    def test_worker_registration_requires_score_ordering_input(self) -> None:
        register_params = set(
            inspect.signature(WorkerRuntime.register_worker_descriptor).parameters
        )

        self.assertEqual(register_params, {"self", "descriptor", "lane_rank"})
        self.assertFalse(hasattr(WorkerResourceCatalog, "register_worker_descriptor"))

    def test_worker_candidate_matcher_batches_constraint_queries(self) -> None:
        match_params = set(
            inspect.signature(WorkerCandidateMatcher.match_worker_candidates).parameters
        )
        init_params = set(inspect.signature(WorkerCandidateMatcher.__init__).parameters)

        self.assertEqual(
            init_params,
            {"self", "catalog", "dynamic_attribute_runtime", "worker_score"},
        )
        self.assertEqual(
            match_params,
            {
                "self",
                "worker_group_id",
                "observed_score_by_worker_id",
                "candidate_constraints",
                "lease_until_millis",
            },
        )

    def test_worker_catalog_requires_group_for_worker_location(self) -> None:
        get_params = set(
            inspect.signature(WorkerResourceCatalog.get_worker_descriptors).parameters
        )
        metadata_params = set(
            inspect.signature(
                WorkerResourceCatalog.update_worker_system_metadata
            ).parameters
        )
        static_params = set(
            inspect.signature(
                WorkerResourceCatalog.refresh_worker_static_attributes
            ).parameters
        )

        self.assertEqual(get_params, {"self", "worker_group_id", "worker_ids"})
        self.assertEqual(
            metadata_params,
            {"self", "worker_group_id", "worker_id", "metadata"},
        )
        self.assertEqual(
            static_params,
            {"self", "worker_group_id", "worker_id", "attributes"},
        )

    def test_dynamic_attribute_runtime_exposes_bounded_owner_operations(self) -> None:
        update_params = set(
            inspect.signature(
                WorkerDynamicAttributeRuntime.update_worker_dynamic_attributes
            ).parameters
        )
        query_params = set(
            inspect.signature(
                WorkerDynamicAttributeRuntime.get_worker_dynamic_attribute_values
            ).parameters
        )

        self.assertEqual(
            update_params,
            {
                "self",
                "worker_group_id",
                "worker_id",
                "updates",
                "observed_at_millis",
            },
        )
        self.assertEqual(
            query_params,
            {"self", "worker_group_id", "attribute_name", "worker_ids"},
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

        def query_battery(
            worker_group_id: str,
            worker_ids: tuple[str, ...],
        ) -> dict[str, DynamicAttributeReadResult]:
            self.assertEqual(worker_group_id, descriptor.worker_group_id)
            results: dict[str, DynamicAttributeReadResult] = {}
            for worker_id in worker_ids:
                value = values.get(worker_id)
                if value is None:
                    results[worker_id] = DynamicAttributeReadResult(
                        status=WorkerRuntimeStatus.NOT_FOUND
                    )
                    continue
                payload, observed_at_millis = value
                results[worker_id] = DynamicAttributeReadResult(
                    status=WorkerRuntimeStatus.OK,
                    value=payload,
                    observed_at_millis=observed_at_millis,
                )
            return results

        update_dynamic_attribute_handlers = {"battery": update_battery}
        query_dynamic_attribute_handlers = {"battery": query_battery}

        result = update_dynamic_attribute_handlers["battery"](
            descriptor.worker_id,
            87,
            10_000,
        )
        read = query_dynamic_attribute_handlers["battery"](
            descriptor.worker_group_id,
            (descriptor.worker_id,),
        )[descriptor.worker_id]

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.value, 87)
        self.assertEqual(descriptor.dynamic_attribute_names, frozenset({"battery"}))


if __name__ == "__main__":
    unittest.main()
