import unittest

import kernel_design.executable_spec as executable_spec
import kernel_design.executable_spec.assembly as assembly
import kernel_design.executable_spec.scheduling as scheduling
from kernel_design.executable_spec import TaskType
from kernel_design.executable_spec.scheduling.task_scheduling_profile import (
    TaskAllocationRuleOwner,
    resolve_task_scheduling_profile,
)
from kernel_design.executable_spec.scheduling.worker_candidate import (
    WorkerCandidateAcquisitionStrategy,
)


class TaskSchedulingProfileTest(unittest.TestCase):
    def test_task_types_resolve_complete_fixed_policy_bundles(self) -> None:
        task_driven = resolve_task_scheduling_profile(TaskType.TASK_DRIVEN)
        item_driven = resolve_task_scheduling_profile(TaskType.ITEM_DRIVEN)

        self.assertIs(TaskAllocationRuleOwner.TASK, task_driven.allocation_rule_owner)
        self.assertTrue(task_driven.candidate_precomputation_enabled)
        self.assertIs(
            WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
            task_driven.dispatch_acquisition_strategy,
        )
        self.assertIs(
            TaskAllocationRuleOwner.TASK_ITEM,
            item_driven.allocation_rule_owner,
        )
        self.assertFalse(item_driven.candidate_precomputation_enabled)
        self.assertIs(
            WorkerCandidateAcquisitionStrategy.TARGETED,
            item_driven.dispatch_acquisition_strategy,
        )

    def test_profile_is_internal_and_rejects_arbitrary_policy_input(self) -> None:
        with self.assertRaises(ValueError):
            resolve_task_scheduling_profile("TASK_DRIVEN")  # type: ignore[arg-type]

        self.assertFalse(hasattr(executable_spec, "ResolvedTaskSchedulingProfile"))
        self.assertFalse(hasattr(assembly, "ResolvedTaskSchedulingProfile"))
        for internal_name in (
            "WorkerCandidateAcquirer",
            "WorkerCandidateAcquisitionStrategy",
            "WorkerCandidateRequest",
        ):
            self.assertFalse(hasattr(executable_spec, internal_name))
            self.assertFalse(hasattr(scheduling, internal_name))
            self.assertFalse(hasattr(assembly, internal_name))


if __name__ == "__main__":
    unittest.main()
