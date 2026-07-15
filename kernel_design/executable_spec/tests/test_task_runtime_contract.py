from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.executable_spec as executable_spec
from kernel_design.executable_spec import (
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskResourceCatalog,
    TaskRuntime,
)


class TaskRuntimeContractTest(unittest.TestCase):
    def test_task_descriptor_keeps_only_allocation_metadata(self) -> None:
        descriptor = TaskDescriptor(
            task_id="task-1",
            worker_group_id="workers-a",
            allocation_rule={"dynamic.battery": {"$gte": 20}},
            config={
                "priority": "80",
                "maximumCandidateWorkers": "20",
                "runningVisibleMinimumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        )

        self.assertEqual(
            {field.name for field in fields(TaskDescriptor)},
            {"task_id", "worker_group_id", "allocation_rule", "config"},
        )
        self.assertEqual(descriptor.config["priority"], "80")
        self.assertEqual(descriptor.config["maxRetryTimes"], "3")
        self.assertFalse(hasattr(descriptor, "project_id"))

    def test_task_item_keeps_only_canonical_record_fields(self) -> None:
        item = TaskItem(
            message_id="message-1",
            event_code="image.resize",
            created_at_millis=1_000,
            payload={"source": "s3://input"},
        )

        self.assertEqual(
            {field.name for field in fields(TaskItem)},
            {
                "message_id",
                "event_code",
                "created_at_millis",
                "payload",
                "priority",
                "expire_at_millis",
            },
        )
        self.assertEqual(5, item.priority)
        self.assertIsNone(item.expire_at_millis)
        self.assertFalse(hasattr(item, "score"))
        self.assertFalse(hasattr(item, "retry_count"))

    def test_task_item_validates_payload_priority_and_expiry(self) -> None:
        invalid_items = (
            {
                "message_id": "message-1",
                "event_code": "event",
                "created_at_millis": 1,
            },
            {
                "message_id": "message-1",
                "event_code": "event",
                "created_at_millis": 1,
                "payload": "ref",
            },
            {
                "message_id": "message-1",
                "event_code": "event",
                "created_at_millis": 1,
                "payload": {},
                "priority": 11,
            },
            {
                "message_id": "message-1",
                "event_code": "event",
                "created_at_millis": 1,
                "payload": {},
                "expire_at_millis": 1,
            },
        )

        for item in invalid_items:
            with self.subTest(item=item), self.assertRaises((TypeError, ValueError)):
                TaskItem(**item)

    def test_task_item_append_result_is_narrow(self) -> None:
        result = TaskItemAppendResult(TaskItemAppendStatus.RETRYABLE, "retry")

        self.assertEqual(TaskItemAppendStatus.RETRYABLE, result.status)
        self.assertEqual(
            {
                "appended",
                "retryable",
                "not_found",
                "invalid",
            },
            {status.value for status in TaskItemAppendStatus},
        )

    def test_creation_result_is_narrow(self) -> None:
        result = TaskCreationResult(
            status=TaskCreationStatus.CONFLICT,
            reason="task already exists",
        )

        self.assertEqual(result.status, TaskCreationStatus.CONFLICT)
        self.assertEqual(
            {status.value for status in TaskCreationStatus},
            {"created", "retryable", "conflict", "invalid"},
        )

    def test_task_descriptor_rejects_invalid_allocation_contracts(self) -> None:
        base_config = {
            "priority": "80",
            "maximumCandidateWorkers": "10",
            "runningVisibleMinimumCandidateWorkers": "2",
            "maxRetryTimes": "3",
        }

        invalid_configs = (
            {**base_config, "priority": "0"},
            {**base_config, "maximumCandidateWorkers": "many"},
            {
                **base_config,
                "runningVisibleMinimumCandidateWorkers": "11",
            },
            {**base_config, "maxRetryTimes": "99"},
            {**base_config, "maxRetryTimes": "many"},
            {key: value for key, value in base_config.items() if key != "priority"},
            {**base_config, "unknown": "1"},
        )
        for config in invalid_configs:
            with self.subTest(config=config), self.assertRaises(ValueError):
                TaskDescriptor(
                    task_id="task-1",
                    worker_group_id="workers-a",
                    allocation_rule={"static.runtime": {"$eq": "python"}},
                    config=config,
                )


    def test_task_runtime_surface_exposes_record_operations(self) -> None:
        self.assertEqual(
            TaskRuntime.__abstractmethods__,
            {"create_task", "append_items", "load_task_items"},
        )
        self.assertEqual(
            set(inspect.signature(TaskRuntime.create_task).parameters),
            {"self", "descriptor", "suffix"},
        )
        self.assertEqual(
            set(inspect.signature(TaskRuntime.append_items).parameters),
            {"self", "task_id", "items"},
        )
        self.assertEqual(
            set(inspect.signature(TaskRuntime.load_task_items).parameters),
            {"self", "task_id", "message_ids"},
        )

    def test_task_resource_catalog_exposes_only_allocation_load(self) -> None:
        self.assertEqual(
            TaskResourceCatalog.__abstractmethods__,
            {"load_task_allocation_descriptors"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskResourceCatalog.load_task_allocation_descriptors
                ).parameters
            ),
            {"self", "task_ids"},
        )
        self.assertFalse(hasattr(TaskResourceCatalog, "get_task_descriptors"))
        self.assertFalse(hasattr(TaskResourceCatalog, "get_task_descriptor"))
        self.assertFalse(hasattr(TaskResourceCatalog, "update_task_descriptor"))
        self.assertFalse(hasattr(TaskResourceCatalog, "delete_task_descriptor"))
        self.assertFalse(hasattr(TaskResourceCatalog, "list_task_descriptors"))
        self.assertFalse(hasattr(TaskResourceCatalog, "register_task_descriptor"))

    def test_task_runtime_contracts_are_package_exports(self) -> None:
        self.assertIs(executable_spec.TaskRuntime, TaskRuntime)
        self.assertIs(executable_spec.TaskDescriptor, TaskDescriptor)
        self.assertIs(executable_spec.TaskItem, TaskItem)
        self.assertIs(executable_spec.TaskItemAppendResult, TaskItemAppendResult)
        self.assertIs(executable_spec.TaskItemAppendStatus, TaskItemAppendStatus)
        self.assertIs(executable_spec.TaskResourceCatalog, TaskResourceCatalog)


if __name__ == "__main__":
    unittest.main()
