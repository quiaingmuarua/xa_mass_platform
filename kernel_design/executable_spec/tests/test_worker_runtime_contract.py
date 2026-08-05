from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerPropertyIndex,
    WorkerPropertyIndexRuntime,
    WorkerResourceCatalog,
    WorkerRuntime,
    WorkerRuntimeStatus,
    WorkerScoreCore,
)


class WorkerRuntimeContractTest(unittest.TestCase):
    def test_worker_resource_dtos_express_two_property_sources(self) -> None:
        self.assertEqual(
            [field.name for field in fields(WorkerDeclaration)],
            [
                "worker_id",
                "worker_group_id",
                "endpoint_manager_id",
                "worker_properties",
            ],
        )
        self.assertEqual(
            [field.name for field in fields(WorkerDescriptor)],
            [
                "worker_id",
                "worker_group_id",
                "endpoint_manager_id",
                "worker_properties",
                "platform_properties",
            ],
        )
        self.assertEqual(
            [field.name for field in fields(WorkerGroupDescriptor)],
            [
                "worker_group_id",
                "attributes",
                "event_codes",
            ],
        )

    def test_owner_surfaces_keep_properties_and_index_independent(self) -> None:
        self.assertEqual(
            WorkerRuntime.__abstractmethods__,
            {"upsert_worker"},
        )
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "patch_worker_platform_properties",
                "sample_worker_descriptors",
                "upsert_worker_group",
            },
        )

    def test_worker_upsert_hides_score_ordering_input(self) -> None:
        self.assertEqual(
            set(inspect.signature(WorkerRuntime.upsert_worker).parameters),
            {"self", "declaration"},
        )
        self.assertFalse(hasattr(WorkerResourceCatalog, "register_worker"))

    def test_worker_score_observation_and_lease_contract_is_unchanged(self) -> None:
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.acquire_hot_acquire_candidates
                ).parameters
            ),
            {"self", "home_bucket_id", "limit"},
        )
        lease_parameters = {
            "self",
            "home_bucket_id",
            "observed_scores",
            "target_time_millis",
        }
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.acquire_observed_hot_score_leases
                ).parameters
            ),
            lease_parameters,
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.renew_active_hot_score_leases
                ).parameters
            ),
            lease_parameters,
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.observe_due_hot_scores
                ).parameters
            ),
            {"self", "home_bucket_id", "worker_ids"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.reconcile_worker_hot_acquire
                ).parameters
            ),
            {"self", "home_bucket_id", "worker_id"},
        )

    def test_worker_catalog_reads_remain_group_local_and_bounded(self) -> None:
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerResourceCatalog.get_worker_descriptors
                ).parameters
            ),
            {"self", "worker_group_id", "worker_ids"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerResourceCatalog.patch_worker_platform_properties
                ).parameters
            ),
            {"self", "worker_group_id", "worker_id", "properties"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerResourceCatalog.sample_worker_descriptors
                ).parameters
            ),
            {"self", "worker_group_id", "sample_limit"},
        )
        self.assertEqual(
            WorkerResourceCatalog.MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT,
            100,
        )
        self.assertEqual(
            WorkerPropertyIndex.__abstractmethods__,
            {"load", "update"},
        )
        self.assertEqual(
            WorkerPropertyIndexRuntime.__abstractmethods__,
            {
                "load_indexed_property_values",
                "update_indexed_properties",
            },
        )

    def test_property_index_operations_are_bounded_and_field_routed(self) -> None:
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerPropertyIndex.load
                ).parameters
            ),
            {
                "self",
                "worker_group_id",
                "worker_ids",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerPropertyIndexRuntime.load_indexed_property_values
                ).parameters
            ),
            {
                "self",
                "worker_group_id",
                "index_field",
                "worker_ids",
            },
        )
        self.assertEqual(
            WorkerPropertyIndexRuntime.MAX_INDEXED_PROPERTY_READ_LIMIT,
            100,
        )
        expected_update = {"self", "worker_group_id", "worker_id", "updates"}
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerPropertyIndexRuntime.update_indexed_properties
                ).parameters
            ),
            expected_update,
        )

    def test_property_index_contracts_are_reexported(self) -> None:
        self.assertIn(
            "WorkerPropertyIndex",
            executable_spec.__all__,
        )
        self.assertIn(
            "WorkerPropertyIndexRuntime",
            executable_spec.__all__,
        )
        self.assertNotIn(
            "WorkerPropertySnapshotRuntime",
            executable_spec.__all__,
        )


if __name__ == "__main__":
    unittest.main()
