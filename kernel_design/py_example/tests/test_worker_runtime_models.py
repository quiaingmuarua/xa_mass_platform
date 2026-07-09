from __future__ import annotations

import unittest
from dataclasses import fields

from kernel_design.py_example import (
    DynamicAttributeHandler,
    DynamicAttributeReadResult,
    WorkerDescriptor,
    WorkerDemand,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)


class RecordingDynamicAttributeHandler(DynamicAttributeHandler):
    def __init__(self, attribute_name: str) -> None:
        self._attribute_name = attribute_name
        self.values: dict[str, tuple[object, int]] = {}

    @property
    def attribute_name(self) -> str:
        return self._attribute_name

    def update(
        self,
        *,
        worker_id: str,
        payload: object,
        observed_at_millis: int,
    ) -> WorkerRuntimeResult:
        self.values[worker_id] = (payload, observed_at_millis)
        return WorkerRuntimeResult(status=WorkerRuntimeStatus.OK)

    def read(
        self,
        *,
        worker_id: str,
    ) -> DynamicAttributeReadResult:
        value = self.values.get(worker_id)
        if value is None:
            return DynamicAttributeReadResult(status=WorkerRuntimeStatus.NOT_FOUND)
        payload, observed_at_millis = value
        return DynamicAttributeReadResult(
            status=WorkerRuntimeStatus.OK,
            value=payload,
            observed_at_millis=observed_at_millis,
        )


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

    def test_worker_demand_is_scoped_to_selected_worker_group(self) -> None:
        demand = WorkerDemand(
            worker_group_id="image-workers",
            event_code="resize",
            target_worker_id="worker-1",
            required_static_attributes={"runtime": "python"},
        )

        self.assertEqual(demand.worker_group_id, "image-workers")
        self.assertEqual(demand.target_worker_id, "worker-1")
        self.assertEqual(demand.required_dynamic_attributes, {})

    def test_dynamic_attribute_value_lives_behind_handler(self) -> None:
        descriptor = WorkerDescriptor(
            worker_id="worker-1",
            worker_group_id="image-workers",
            system_attributes={},
            static_attributes={"runtimeVersion": "1.0.0"},
            dynamic_attributes=frozenset({"battery"}),
        )
        handler = RecordingDynamicAttributeHandler("battery")

        result = handler.update(
            worker_id=descriptor.worker_id,
            payload=87,
            observed_at_millis=10_000,
        )
        read = handler.read(worker_id=descriptor.worker_id)

        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.status, WorkerRuntimeStatus.OK)
        self.assertEqual(read.value, 87)
        self.assertEqual(descriptor.dynamic_attributes, frozenset({"battery"}))


if __name__ == "__main__":
    unittest.main()
