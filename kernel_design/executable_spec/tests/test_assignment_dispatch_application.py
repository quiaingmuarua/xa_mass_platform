from __future__ import annotations

import unittest
from collections.abc import Callable
from dataclasses import fields
from threading import Event
from time import monotonic, sleep
from unittest.mock import Mock, call

from kernel_design.executable_spec.scheduling import (
    TaskDispatchConfig,
    TaskDispatchPacer,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
)
from kernel_design.executable_spec.assembly.assignment_dispatch_application import (
    AssignmentDispatchApplication,
    AssignmentDispatchApplicationConfig,
)


class AssignmentDispatchApplicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.worker_allocation_pacer = Mock(spec=TaskWorkerAllocationPacer)
        self.running_activation_pacer = Mock(spec=TaskRunningActivationPacer)
        self.task_dispatch_pacer = Mock(spec=TaskDispatchPacer)
        self.application = AssignmentDispatchApplication(
            self.worker_allocation_pacer,
            self.running_activation_pacer,
            self.task_dispatch_pacer,
        )

    def tearDown(self) -> None:
        self.application.stop(timeout_millis=1_000)

    def test_config_exposes_three_round_configs_and_intervals(self) -> None:
        self.assertEqual(
            [
                "worker_allocation",
                "running_activation",
                "task_dispatch",
                "worker_allocation_interval_millis",
                "running_activation_interval_millis",
                "task_dispatch_interval_millis",
            ],
            [field.name for field in fields(AssignmentDispatchApplicationConfig)],
        )
        for intervals in ((0, 1, 1), (1, 0, 1), (1, 1, 0), (-1, 1, 1)):
            with self.subTest(intervals=intervals), self.assertRaises(ValueError):
                self.config(intervals=intervals)

    def test_start_runs_each_pacer_immediately_and_stop_interrupts_wait(self) -> None:
        config = self.config(intervals=(1_000, 1_000, 1_000))

        self.application.start(config=config)
        self.wait_until(
            lambda: all(
                pacer_method.call_count >= 1
                for pacer_method in (
                    self.worker_allocation_pacer.allocate_candidate_workers,
                    self.running_activation_pacer.activate_running_visible_tasks,
                    self.task_dispatch_pacer.dispatch_tasks,
                )
            )
        )
        self.application.stop(timeout_millis=1_000)

        self.assertEqual(
            [call(config=config.worker_allocation)],
            self.worker_allocation_pacer.allocate_candidate_workers.call_args_list,
        )
        self.assertEqual(
            [call(config=config.running_activation)],
            self.running_activation_pacer.activate_running_visible_tasks.call_args_list,
        )
        self.assertEqual(
            [call(config=config.task_dispatch)],
            self.task_dispatch_pacer.dispatch_tasks.call_args_list,
        )
        call_counts = self.pacer_call_counts()
        sleep(0.03)
        self.assertEqual(call_counts, self.pacer_call_counts())

    def test_failed_round_is_logged_and_the_same_loop_continues(self) -> None:
        recovered = Event()
        allocation_calls = 0

        def allocation_round(*, config: TaskWorkerAllocationConfig) -> int:
            nonlocal allocation_calls
            allocation_calls += 1
            if allocation_calls == 1:
                raise RuntimeError("temporary allocation failure")
            recovered.set()
            return 1

        self.worker_allocation_pacer.allocate_candidate_workers.side_effect = (
            allocation_round
        )
        config = self.config(intervals=(5, 1_000, 1_000))

        with self.assertLogs(
            "kernel_design.executable_spec.assembly."
            "assignment_dispatch_application",
            level="ERROR",
        ) as captured_logs:
            self.application.start(config=config)
            self.assertTrue(recovered.wait(timeout=1))
            self.application.stop(timeout_millis=1_000)

        self.assertGreaterEqual(allocation_calls, 2)
        self.assertTrue(
            any("worker-allocation round failed" in row for row in captured_logs.output)
        )

    def test_duplicate_start_is_rejected_and_clean_stop_allows_restart(self) -> None:
        config = self.config(intervals=(1_000, 1_000, 1_000))
        self.application.start(config=config)
        with self.assertRaises(RuntimeError):
            self.application.start(config=config)
        self.application.stop(timeout_millis=1_000)

        self.application.start(config=config)
        self.application.stop(timeout_millis=1_000)

    def test_stop_timeout_does_not_claim_a_blocked_round_stopped(self) -> None:
        round_started = Event()
        release_round = Event()

        def blocked_round(*, config: TaskWorkerAllocationConfig) -> int:
            round_started.set()
            release_round.wait(timeout=1)
            return 0

        self.worker_allocation_pacer.allocate_candidate_workers.side_effect = (
            blocked_round
        )
        self.application.start(config=self.config(intervals=(1_000, 1_000, 1_000)))
        self.assertTrue(round_started.wait(timeout=1))

        try:
            with self.assertRaises(TimeoutError):
                self.application.stop(timeout_millis=10)
        finally:
            release_round.set()
            self.application.stop(timeout_millis=1_000)

    def config(
        self,
        *,
        intervals: tuple[int, int, int],
    ) -> AssignmentDispatchApplicationConfig:
        return AssignmentDispatchApplicationConfig(
            worker_allocation=TaskWorkerAllocationConfig(
                task_batch_limit=10,
                worker_lease_duration_millis=5_000,
            ),
            running_activation=TaskRunningActivationConfig(task_batch_limit=10),
            task_dispatch=TaskDispatchConfig(
                task_batch_limit=10,
                per_task_dispatch_limit=20,
                item_claim_lease_duration_millis=5_000,
            ),
            worker_allocation_interval_millis=intervals[0],
            running_activation_interval_millis=intervals[1],
            task_dispatch_interval_millis=intervals[2],
        )

    def pacer_call_counts(self) -> tuple[int, int, int]:
        return (
            self.worker_allocation_pacer.allocate_candidate_workers.call_count,
            self.running_activation_pacer.activate_running_visible_tasks.call_count,
            self.task_dispatch_pacer.dispatch_tasks.call_count,
        )

    def wait_until(
        self,
        condition: Callable[[], bool],
        *,
        timeout: float = 1.0,
    ) -> None:
        deadline = monotonic() + timeout
        while monotonic() < deadline:
            if condition():
                return
            sleep(0.005)
        self.fail("condition was not satisfied before timeout")


if __name__ == "__main__":
    unittest.main()
