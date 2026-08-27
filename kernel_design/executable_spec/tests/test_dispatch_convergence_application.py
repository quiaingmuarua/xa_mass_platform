from __future__ import annotations

import time
import unittest
from threading import Event
from unittest.mock import Mock

from kernel_design.executable_spec import (
    DueTaskObservation,
    TaskDescriptor,
    TaskIdleDisposition,
    TaskSchedulingBatchSource,
    TaskScoreBand,
    TaskScoreState,
    WorkerAllocationMechanism,
)
from kernel_design.executable_spec.assembly.dispatch_convergence_application import (
    AssignmentDispatchConfig,
    DispatchConvergenceApplication,
    WorkerServiceabilityDispatchLaneConfig,
)
from kernel_design.executable_spec.scheduling import (
    TaskDispatchConfig,
    TaskDispatchPolicy,
    TaskRunningActivationConfig,
    TaskRunningActivationPolicy,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPolicy,
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityDispatchPolicy,
)


class DispatchConvergenceApplicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = Mock(spec=TaskSchedulingBatchSource)
        self.activation = Mock(spec=TaskRunningActivationPolicy)
        self.allocation = Mock(spec=TaskWorkerAllocationPolicy)
        self.dispatch = Mock(spec=TaskDispatchPolicy)
        self.serviceability = Mock(spec=WorkerServiceabilityDispatchPolicy)
        self.application = DispatchConvergenceApplication(
            self.source,
            self.activation,
            self.allocation,
            self.dispatch,
            self.serviceability,
        )

    def tearDown(self) -> None:
        if self.application.state in {"RUNNING", "FAILED"}:
            self.application.stop(timeout_millis=2_000)

    def test_one_running_read_is_shared_across_three_parallel_lanes(self) -> None:
        running = (observation("running"),)
        admission = (observation("admission", admission=True),)
        self.source.acquire_running_tasks.return_value = running
        self.source.acquire_admission_tasks.return_value = admission
        completed = Event()
        seen: list[tuple[str, object]] = []

        def record(name: str):
            def action(batch, **_kwargs):
                seen.append((name, batch))
                if len(seen) >= 4:
                    completed.set()
                return 0
            return action

        self.activation.activate_running_visible_tasks.side_effect = record(
            "activation"
        )
        self.allocation.allocate_candidate_workers.side_effect = record(
            "allocation"
        )
        self.dispatch.dispatch_tasks.side_effect = record("dispatch")
        self.serviceability.dispatch_probes.side_effect = record(
            "serviceability"
        )

        self.application.start(
            assignment=assignment_config(interval_millis=1_000),
            serviceability=serviceability_config(interval_millis=1_000),
        )

        self.assertTrue(completed.wait(2))
        running_batches = [
            batch for name, batch in seen if name != "activation"
        ]
        self.assertEqual(3, len(running_batches))
        self.assertTrue(all(batch is running for batch in running_batches))
        self.assertIs(
            admission,
            next(batch for name, batch in seen if name == "activation"),
        )
        self.assertEqual(1, self.source.acquire_running_tasks.call_count)

    def test_blocked_allocation_does_not_block_other_running_lanes(self) -> None:
        running = (observation("running"),)
        self.source.acquire_running_tasks.return_value = running
        self.source.acquire_admission_tasks.return_value = ()
        allocation_started = Event()
        release_allocation = Event()
        dispatch_completed = Event()
        serviceability_completed = Event()

        def block_allocation(*_args, **_kwargs):
            allocation_started.set()
            release_allocation.wait(2)
            return 0

        self.allocation.allocate_candidate_workers.side_effect = block_allocation
        self.dispatch.dispatch_tasks.side_effect = (
            lambda *_args, **_kwargs: dispatch_completed.set() or 0
        )
        self.serviceability.dispatch_probes.side_effect = (
            lambda *_args, **_kwargs: serviceability_completed.set() or 0
        )

        self.application.start(
            assignment=assignment_config(interval_millis=10),
            serviceability=serviceability_config(interval_millis=10),
        )

        self.assertTrue(allocation_started.wait(2))
        self.assertTrue(dispatch_completed.wait(2))
        self.assertTrue(serviceability_completed.wait(2))
        time.sleep(0.05)
        self.assertEqual(
            1,
            self.allocation.allocate_candidate_workers.call_count,
            "a busy lane must skip newly observed batches",
        )
        release_allocation.set()

    def test_runtime_exception_is_lane_local_and_later_rounds_continue(self) -> None:
        self.source.acquire_running_tasks.return_value = (observation("task"),)
        self.source.acquire_admission_tasks.return_value = ()
        recovered = Event()
        calls = 0

        def fail_once(*_args, **_kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise RuntimeError("expected test failure")
            recovered.set()
            return 0

        self.allocation.allocate_candidate_workers.side_effect = fail_once
        self.application.start(
            assignment=assignment_config(interval_millis=5),
            serviceability=None,
        )

        self.assertTrue(recovered.wait(2))
        self.assertTrue(self.application.is_running())

    def test_duplicate_start_fails_and_stop_is_idempotent(self) -> None:
        self.source.acquire_running_tasks.return_value = ()
        self.source.acquire_admission_tasks.return_value = ()
        config = assignment_config(interval_millis=10)
        self.application.start(assignment=config, serviceability=None)

        with self.assertRaises(RuntimeError):
            self.application.start(assignment=config, serviceability=None)

        self.application.stop(timeout_millis=2_000)
        self.application.stop(timeout_millis=2_000)
        self.assertEqual("STOPPED", self.application.state)


def assignment_config(*, interval_millis: int) -> AssignmentDispatchConfig:
    return AssignmentDispatchConfig(
        worker_allocation=TaskWorkerAllocationConfig(5_000),
        running_activation=TaskRunningActivationConfig(1_000),
        task_dispatch=TaskDispatchConfig(100, 5_000),
        worker_allocation_interval_millis=interval_millis,
        running_activation_interval_millis=interval_millis,
        task_dispatch_interval_millis=interval_millis,
    )


def serviceability_config(
    *,
    interval_millis: int,
) -> WorkerServiceabilityDispatchLaneConfig:
    return WorkerServiceabilityDispatchLaneConfig(
        dispatch=WorkerServiceabilityDispatchConfig(),
        interval_millis=interval_millis,
    )


def observation(
    task_id: str,
    *,
    admission: bool = False,
) -> DueTaskObservation:
    band = (
        TaskScoreBand.ADMISSION_VISIBLE
        if admission
        else TaskScoreBand.RUNNING_VISIBLE
    )
    return DueTaskObservation(
        task_id=task_id,
        score_state=TaskScoreState(
            task_id=task_id,
            score=1,
            band=band,
            time_millis=100,
            suffix=0,
        ),
        descriptor=TaskDescriptor(
            task_id=task_id,
            worker_group_id="group-1",
            worker_allocation_mechanism=(
                WorkerAllocationMechanism.DIRECT_ITEM_RULE
            ),
            idle_disposition=TaskIdleDisposition.PARK_WHEN_IDLE,
            allocation_rule=None,
            config={
                "priority": "0",
                "maximumCandidateWorkers": "1",
                "maxRetryTimes": "1",
            },
        ),
    )


if __name__ == "__main__":
    unittest.main()
