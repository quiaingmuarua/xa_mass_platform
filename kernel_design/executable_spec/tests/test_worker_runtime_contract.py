from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
    WorkerGroupDescriptor,
    WorkerResourceCatalog,
    WorkerRuntime,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
    WorkerScoreCore,
)
from kernel_design.executable_spec.kernel.worker_runtime import DynamicAttributeReadResult


class WorkerRuntimeContractTest(unittest.TestCase):
    def test_worker_declaration_contains_only_worker_owned_fields(self) -> None:
        self.assertEqual(
            {field.name for field in fields(WorkerDeclaration)},
            {
                "worker_id",
                "worker_group_id",
                "endpoint_manager_id",
                "attributes",
                "dynamic_attribute_names",
            },
        )

    def test_worker_descriptor_first_layer_shape_has_endpoint_owner_no_version(self) -> None:
        field_names = {field.name for field in fields(WorkerDescriptor)}

        self.assertEqual(
            field_names,
            {
                "worker_id",
                "worker_group_id",
                "endpoint_manager_id",
                "platform_attributes",
                "attributes",
                "dynamic_attribute_names",
            },
        )

    def test_worker_group_event_codes_are_group_promise_metadata(self) -> None:
        descriptor = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image"},
            event_codes=frozenset({"resize", "thumbnail"}),
            item_allocation_fields=frozenset({"workerId"}),
        )

        self.assertEqual(
            {
                "worker_group_id",
                "attributes",
                "event_codes",
                "item_allocation_fields",
            },
            {field.name for field in fields(WorkerGroupDescriptor)},
        )
        self.assertEqual(descriptor.worker_group_id, "image-workers")
        self.assertIn("resize", descriptor.event_codes)
        self.assertEqual(frozenset({"workerId"}), descriptor.item_allocation_fields)

    def test_worker_runtime_interfaces_expose_narrow_owner_surfaces(self) -> None:
        self.assertEqual(
            WorkerRuntime.__abstractmethods__,
            {"upsert_worker"},
        )
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "upsert_worker_group",
                "update_worker_platform_attributes",
            },
        )
        self.assertEqual(
            WorkerDynamicAttributeRuntime.__abstractmethods__,
            {
                "get_worker_dynamic_attribute_values",
                "query_candidate_worker_ids",
                "supports_candidate_query",
                "update_worker_dynamic_attributes",
            },
        )
        self.assertFalse(hasattr(executable_spec, "WorkerAdmission"))
        self.assertFalse(hasattr(executable_spec, "WorkerAdmissionResult"))
        self.assertFalse(hasattr(executable_spec, "WorkerAdmissionRuntime"))
        self.assertFalse(hasattr(executable_spec, "WorkerMatchResult"))
        self.assertFalse(hasattr(executable_spec, "WorkerReservation"))
        self.assertFalse(hasattr(executable_spec, "WorkerReservationHandle"))
        self.assertFalse(hasattr(executable_spec, "WorkerReservationResult"))
        self.assertFalse(hasattr(executable_spec, "WorkerReservationRuntime"))
        self.assertFalse(hasattr(executable_spec, "WorkerValidationResult"))

    def test_worker_upsert_hides_score_ordering_input(self) -> None:
        upsert_params = set(
            inspect.signature(WorkerRuntime.upsert_worker).parameters
        )

        self.assertEqual(upsert_params, {"self", "declaration"})
        self.assertFalse(hasattr(WorkerResourceCatalog, "upsert_worker"))

    def test_worker_score_separates_hot_observation_from_exact_lease(self) -> None:
        acquire_params = set(
            inspect.signature(
                WorkerScoreCore.acquire_hot_acquire_candidates
            ).parameters
        )

        self.assertEqual(
            acquire_params,
            {"self", "home_bucket_id", "limit"},
        )
        lease_params = set(
            inspect.signature(
                WorkerScoreCore.acquire_observed_hot_score_leases
            ).parameters
        )
        self.assertEqual(
            lease_params,
            {
                "self",
                "home_bucket_id",
                "observed_scores",
                "target_time_millis",
            },
        )
        rewrite_params = set(
            inspect.signature(WorkerScoreCore.rewrite_current_scores).parameters
        )
        self.assertEqual(
            rewrite_params,
            {
                "self",
                "home_bucket_id",
                "worker_ids",
                "target_time_millis",
                "target_lane_rank",
            },
        )
        renew_params = set(
            inspect.signature(
                WorkerScoreCore.renew_active_hot_score_leases
            ).parameters
        )
        self.assertEqual(renew_params, lease_params)
        observe_params = set(
            inspect.signature(WorkerScoreCore.observe_due_hot_scores).parameters
        )
        self.assertEqual(
            observe_params,
            {"self", "home_bucket_id", "worker_ids"},
        )
        reconcile_params = set(
            inspect.signature(WorkerScoreCore.reconcile_worker_hot_acquire).parameters
        )
        self.assertEqual(
            reconcile_params,
            {"self", "home_bucket_id", "worker_id"},
        )
        recovery_params = set(
            inspect.signature(
                WorkerScoreCore.demote_observed_worker_leases_to_recovery
            ).parameters
        )
        self.assertEqual(
            recovery_params,
            {"self", "home_bucket_id", "observed_scores"},
        )
        release_params = set(
            inspect.signature(WorkerScoreCore.release_score_holds).parameters
        )
        self.assertEqual(
            release_params,
            {
                "self",
                "home_bucket_id",
                "observed_scores",
                "release_time_millis",
            },
        )

    def test_worker_catalog_requires_group_for_worker_location(self) -> None:
        get_params = set(
            inspect.signature(WorkerResourceCatalog.get_worker_descriptors).parameters
        )
        platform_params = set(
            inspect.signature(
                WorkerResourceCatalog.update_worker_platform_attributes
            ).parameters
        )

        self.assertEqual(get_params, {"self", "worker_group_id", "worker_ids"})
        self.assertEqual(
            platform_params,
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
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerDynamicAttributeRuntime.supports_candidate_query
                ).parameters
            ),
            {"self", "attribute_name", "operator_rule"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerDynamicAttributeRuntime.query_candidate_worker_ids
                ).parameters
            ),
            {
                "self",
                "worker_group_id",
                "attribute_name",
                "operator_rule",
                "limit",
            },
        )
        self.assertTrue(hasattr(executable_spec, "DynamicAttributePayload"))
        self.assertTrue(hasattr(executable_spec, "EndpointManagerId"))

    def test_dynamic_attribute_value_lives_behind_function_table(self) -> None:
        descriptor = WorkerDescriptor(
            worker_id="worker-1",
            worker_group_id="image-workers",
            endpoint_manager_id="endpoint-manager-1",
            platform_attributes={},
            attributes={"runtimeVersion": "1.0.0"},
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
