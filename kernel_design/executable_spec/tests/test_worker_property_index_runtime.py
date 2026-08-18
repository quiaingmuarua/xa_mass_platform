from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    MappedWorkerPropertyIndexRuntime,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerPropertyIndex,
    WorkerResourceCatalog,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)


class _Catalog(WorkerResourceCatalog):
    def __init__(self) -> None:
        self.groups = {
            group_id: WorkerGroupDescriptor(
                worker_group_id=group_id,
                attributes={},
                event_codes=frozenset({"observe"}),
            )
            for group_id in ("group-1", "group-2")
        }
        self.workers = {
            (group_id, "worker-1"): WorkerDescriptor(
                worker_id="worker-1",
                worker_group_id=group_id,
                endpoint_manager_id="endpoint-1",
                worker_properties={},
                platform_properties={},
            )
            for group_id in self.groups
        }

    def upsert_worker_group(self, *, descriptor):
        raise NotImplementedError

    def get_worker_group_descriptors(self, *, worker_group_ids):
        return {group_id: self.groups.get(group_id) for group_id in worker_group_ids}

    def get_worker_descriptors(self, *, worker_group_id, worker_ids):
        return {
            worker_id: self.workers.get((worker_group_id, worker_id))
            for worker_id in worker_ids
        }

    def get_worker_group_ids(self, *, worker_ids):
        return {
            worker_id: next(
                (
                    group_id
                    for group_id in self.groups
                    if (group_id, worker_id) in self.workers
                ),
                None,
            )
            for worker_id in worker_ids
        }

    def sample_worker_descriptors(self, *, worker_group_id, sample_limit):
        raise NotImplementedError

    def patch_worker_platform_properties(
        self,
        *,
        worker_group_id,
        worker_id,
        properties,
    ):
        raise NotImplementedError


class _Index(WorkerPropertyIndex):
    def __init__(self) -> None:
        self.updates: list[tuple[str, str, object | None]] = []
        self.loads: list[tuple[str, tuple[str, ...]]] = []
        self.load_result: dict[str, object] = {"worker-1": "cn-east"}
        self.load_error: Exception | None = None
        self.update_error: Exception | None = None

    def update(self, *, worker_group_id, worker_id, value):
        if self.update_error is not None:
            raise self.update_error
        self.updates.append((worker_group_id, worker_id, value))
        return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

    def load(self, *, worker_group_id, worker_ids):
        if self.load_error is not None:
            raise self.load_error
        self.loads.append((worker_group_id, tuple(worker_ids)))
        return self.load_result


class WorkerPropertyIndexRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = _Catalog()
        self.worker_index = _Index()
        self.platform_index = _Index()
        self.runtime = MappedWorkerPropertyIndexRuntime(
            self.catalog,
            {
                "index.worker.region": self.worker_index,
                "index.platform.pool": self.platform_index,
            },
        )

    def test_registry_rejects_invalid_fields_and_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "index.*"):
            MappedWorkerPropertyIndexRuntime(
                self.catalog,
                {"worker.region": _Index()},
            )
        with self.assertRaisesRegex(TypeError, "WorkerPropertyIndex"):
            MappedWorkerPropertyIndexRuntime(
                self.catalog,
                {"index.worker.region": object()},
            )

    def test_updates_route_qualified_fields_without_group_declaration(self) -> None:
        results = self.runtime.update_indexed_properties(
            worker_group_id="group-1",
            worker_id="worker-1",
            updates={
                "index.worker.region": "cn-east",
                "index.platform.pool": "batch",
                "index.missing": "value",
                "worker.invalid": "value",
            },
        )

        self.assertEqual(
            results["index.worker.region"].status,
            WorkerRuntimeStatus.OK,
        )
        self.assertEqual(
            results["index.platform.pool"].status,
            WorkerRuntimeStatus.OK,
        )
        self.assertEqual(
            results["index.missing"].status,
            WorkerRuntimeStatus.NOT_FOUND,
        )
        self.assertEqual(
            results["worker.invalid"].status,
            WorkerRuntimeStatus.INVALID,
        )
        self.assertEqual(
            self.worker_index.updates,
            [("group-1", "worker-1", "cn-east")],
        )

    def test_provider_update_failure_is_field_local(self) -> None:
        self.worker_index.update_error = RuntimeError("unavailable")
        result = self.runtime.update_indexed_properties(
            worker_group_id="group-1",
            worker_id="worker-1",
            updates={"index.worker.region": "cn-east"},
        )
        self.assertEqual(
            result["index.worker.region"].status,
            WorkerRuntimeStatus.STALE,
        )

    def test_load_routes_group_and_bounded_worker_ids_to_same_field_index(self) -> None:
        result = self.runtime.load_indexed_property_values(
            worker_group_id="group-2",
            index_field="index.worker.region",
            worker_ids=["worker-1", "worker-1"],
        )
        self.assertEqual(result, {"worker-1": "cn-east"})
        self.assertEqual(
            self.worker_index.loads,
            [("group-2", ("worker-1",))],
        )

    def test_missing_or_failing_load_is_not_reported_as_empty(self) -> None:
        with self.assertRaisesRegex(LookupError, "not configured"):
            self.runtime.load_indexed_property_values(
                worker_group_id="group-1",
                index_field="index.platform.missing",
                worker_ids=["worker-1"],
            )

        self.worker_index.load_error = RuntimeError("provider failed")
        with self.assertRaisesRegex(RuntimeError, "provider failed"):
            self.runtime.load_indexed_property_values(
                worker_group_id="group-1",
                index_field="index.worker.region",
                worker_ids=["worker-1"],
            )

    def test_load_rejects_empty_and_oversized_worker_batches(self) -> None:
        with self.assertRaisesRegex(ValueError, "1..100"):
            self.runtime.load_indexed_property_values(
                worker_group_id="group-1",
                index_field="index.worker.region",
                worker_ids=[],
            )
        with self.assertRaisesRegex(ValueError, "1..100"):
            self.runtime.load_indexed_property_values(
                worker_group_id="group-1",
                index_field="index.worker.region",
                worker_ids=[f"worker-{index}" for index in range(101)],
            )


if __name__ == "__main__":
    unittest.main()
