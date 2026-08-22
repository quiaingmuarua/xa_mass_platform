from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerGroupDescriptor,
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

    def test_owner_surfaces_keep_worker_and_platform_properties_independent(
        self,
    ) -> None:
        self.assertEqual(
            WorkerRuntime.__abstractmethods__,
            {"upsert_worker"},
        )
        self.assertEqual(
            WorkerResourceCatalog.__abstractmethods__,
            {
                "get_worker_descriptors",
                "get_worker_group_descriptors",
                "get_worker_group_ids",
                "patch_worker_platform_properties",
                "sample_worker_group_descriptors",
                "sample_worker_descriptors",
                "register_worker_group",
            },
        )

    def test_worker_upsert_hides_score_ordering_input(self) -> None:
        self.assertEqual(
            set(inspect.signature(WorkerRuntime.upsert_worker).parameters),
            {"self", "declaration"},
        )
        self.assertFalse(hasattr(WorkerRuntime, "replace_worker_properties"))
        self.assertFalse(hasattr(WorkerResourceCatalog, "register_worker"))

    def test_worker_score_observation_and_transition_contract_is_bounded(self) -> None:
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.acquire_hot_acquire_candidates
                ).parameters
            ),
            {
                "self",
                "home_bucket_id",
                "hot_eligibility_floor_millis",
                "limit",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.acquire_recovery_recheck_candidates
                ).parameters
            ),
            {
                "self",
                "home_bucket_id",
                "maximum_score_exclusive",
                "limit",
            },
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
            {
                "self",
                "home_bucket_id",
                "worker_ids",
                "hot_eligibility_floor_millis",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.acquire_pre_epoch_hot_candidates
                ).parameters
            ),
            {
                "self",
                "home_bucket_id",
                "hot_eligibility_floor_millis",
                "maximum_score_exclusive",
                "limit",
            },
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.initialize_hot_acquire_score
                ).parameters
            ),
            {"self", "home_bucket_id", "worker_id"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    WorkerScoreCore.toggle_current_polarity
                ).parameters
            ),
            {"self", "home_bucket_id", "worker_id", "observed_score"},
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
                    WorkerResourceCatalog.get_worker_group_ids
                ).parameters
            ),
            {"self", "worker_ids"},
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
                    WorkerResourceCatalog.sample_worker_group_descriptors
                ).parameters
            ),
            {"self", "sample_limit"},
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
            WorkerResourceCatalog.MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT,
            100,
        )
        self.assertEqual(
            WorkerResourceCatalog.MAX_WORKER_GROUP_LOOKUP_LIMIT,
            100,
        )

    def test_removed_property_index_contracts_are_not_reexported(self) -> None:
        self.assertNotIn("WorkerPropertyIndex", executable_spec.__all__)
        self.assertNotIn("WorkerPropertyIndexRuntime", executable_spec.__all__)
        self.assertNotIn("MappedWorkerPropertyIndexRuntime", executable_spec.__all__)
        self.assertNotIn(
            "WorkerPropertySnapshotRuntime",
            executable_spec.__all__,
        )


if __name__ == "__main__":
    unittest.main()
