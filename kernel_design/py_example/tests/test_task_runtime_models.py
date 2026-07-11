from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    TaskCreationResult,
    TaskCreationRuntime,
    TaskCreationStatus,
    TaskDescriptor,
    TaskResourceCatalog,
)


class TaskRuntimeModelTest(unittest.TestCase):
    def test_task_descriptor_keeps_only_allocation_metadata(self) -> None:
        descriptor = TaskDescriptor(
            task_id="task-1",
            worker_group_id="workers-a",
            allocation_rule={"dynamic.battery": {"$gte": 20}},
            config={
                "priority": "80",
                "runningVisibleMinimumCandidateWorkers": "10",
            },
        )

        self.assertEqual(
            {field.name for field in fields(TaskDescriptor)},
            {"task_id", "worker_group_id", "allocation_rule", "config"},
        )
        self.assertEqual(descriptor.config["priority"], "80")
        self.assertFalse(hasattr(descriptor, "project_id"))

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

    def test_task_creation_runtime_exposes_only_create(self) -> None:
        self.assertEqual(TaskCreationRuntime.__abstractmethods__, {"create_task"})
        self.assertEqual(
            set(inspect.signature(TaskCreationRuntime.create_task).parameters),
            {"self", "descriptor", "suffix"},
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
        self.assertIs(py_example.TaskCreationRuntime, TaskCreationRuntime)
        self.assertIs(py_example.TaskDescriptor, TaskDescriptor)
        self.assertIs(py_example.TaskResourceCatalog, TaskResourceCatalog)


if __name__ == "__main__":
    unittest.main()
