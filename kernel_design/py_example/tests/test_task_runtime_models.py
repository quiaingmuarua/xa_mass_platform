from __future__ import annotations

import inspect
import unittest
from dataclasses import fields

import kernel_design.py_example as py_example
from kernel_design.py_example import (
    TaskDescriptor,
    TaskDescriptorRegistrationResult,
    TaskDescriptorRegistrationStatus,
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

    def test_registration_result_is_narrow(self) -> None:
        result = TaskDescriptorRegistrationResult(
            status=TaskDescriptorRegistrationStatus.CONFLICT,
            reason="task descriptor already exists",
        )

        self.assertEqual(result.status, TaskDescriptorRegistrationStatus.CONFLICT)
        self.assertEqual(
            {status.value for status in TaskDescriptorRegistrationStatus},
            {"registered", "conflict", "invalid"},
        )

    def test_task_resource_catalog_exposes_only_create_and_allocation_load(self) -> None:
        self.assertEqual(
            TaskResourceCatalog.__abstractmethods__,
            {"register_task_descriptor", "load_task_allocation_descriptors"},
        )
        self.assertEqual(
            set(
                inspect.signature(
                    TaskResourceCatalog.register_task_descriptor
                ).parameters
            ),
            {"self", "descriptor"},
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

    def test_task_runtime_contracts_are_package_exports(self) -> None:
        self.assertIs(py_example.TaskDescriptor, TaskDescriptor)
        self.assertIs(py_example.TaskResourceCatalog, TaskResourceCatalog)


if __name__ == "__main__":
    unittest.main()
