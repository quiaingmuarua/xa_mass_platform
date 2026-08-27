from __future__ import annotations

import threading
import time
import unittest
from unittest.mock import patch

from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
)
from kernel_design.executable_spec.assembly.result_convergence_application import (
    ResultConvergenceApplication,
    _ResultLane,
    _ResultLaneId,
)


def _report() -> DeliveryReport:
    return DeliveryReport.create(
        src=DeliveryEndpoint.WORKER,
        source_id="worker-1",
        dst=DeliveryEndpoint.TASK,
        message_type="test.event",
        outcome_code="200",
        payload="null",
        forward="context",
    )


class _BlockingPolicies:
    def __init__(self, expected_starts: int) -> None:
        self.started = threading.Semaphore(0)
        self.release = threading.Event()
        self._lock = threading.Lock()
        self._expected_starts = expected_starts
        self.global_active = 0
        self.maximum_global_active = 0
        self.active_by_lane = {
            lane_id: 0 for lane_id in _ResultLaneId
        }

    def block(self, lane_id: _ResultLaneId) -> None:
        with self._lock:
            self.global_active += 1
            self.maximum_global_active = max(
                self.maximum_global_active,
                self.global_active,
            )
            self.active_by_lane[lane_id] += 1
        self.started.release()
        try:
            self.release.wait(timeout=1)
        finally:
            with self._lock:
                self.active_by_lane[lane_id] -= 1
                self.global_active -= 1

    def wait_for_all(self) -> bool:
        for _ in range(self._expected_starts):
            if not self.started.acquire(timeout=1):
                return False
        return True

    def active(self, lane_id: _ResultLaneId) -> int:
        with self._lock:
            return self.active_by_lane[lane_id]


class ResultConvergenceApplicationTest(unittest.TestCase):
    def test_initial_capacity_uses_weighted_fair_share(self) -> None:
        policies = _BlockingPolicies(expected_starts=10)
        application = self._application(
            10,
            self._endless_lane(
                _ResultLaneId.TASK_SUCCESS,
                target=4,
                maximum=10,
                policies=policies,
            ),
            self._endless_lane(
                _ResultLaneId.TASK_FAILURE,
                target=3,
                maximum=10,
                policies=policies,
            ),
            self._endless_lane(
                _ResultLaneId.ADAPTER_EVIDENCE,
                target=3,
                maximum=10,
                policies=policies,
            ),
        )

        application.start()
        try:
            self.assertTrue(policies.wait_for_all())
            self.assertEqual(10, policies.global_active)
            self.assertEqual(4, policies.active(_ResultLaneId.TASK_SUCCESS))
            self.assertEqual(3, policies.active(_ResultLaneId.TASK_FAILURE))
            self.assertEqual(
                3,
                policies.active(_ResultLaneId.ADAPTER_EVIDENCE),
            )
            self.assertEqual(10, policies.maximum_global_active)
        finally:
            policies.release.set()
            application.stop(timeout_millis=1_000)

    def test_production_quotas_keep_ordered_lanes_single(self) -> None:
        policies = _BlockingPolicies(expected_starts=10)
        application = self._application(
            10,
            self._endless_lane(
                _ResultLaneId.TASK_SUCCESS,
                target=1,
                maximum=1,
                policies=policies,
            ),
            self._endless_lane(
                _ResultLaneId.TASK_FAILURE,
                target=3,
                maximum=10,
                policies=policies,
            ),
            self._endless_lane(
                _ResultLaneId.ADAPTER_EVIDENCE,
                target=1,
                maximum=1,
                policies=policies,
            ),
        )

        application.start()
        try:
            self.assertTrue(policies.wait_for_all())
            self.assertEqual(1, policies.active(_ResultLaneId.TASK_SUCCESS))
            self.assertEqual(8, policies.active(_ResultLaneId.TASK_FAILURE))
            self.assertEqual(
                1,
                policies.active(_ResultLaneId.ADAPTER_EVIDENCE),
            )
            self.assertEqual(10, policies.maximum_global_active)
        finally:
            policies.release.set()
            application.stop(timeout_millis=1_000)

    def test_policy_exception_discards_batch_and_lane_continues(self) -> None:
        consumes = 0
        policies = 0
        recovered = threading.Event()

        def consume(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal consumes
            consumes += 1
            return (_report(),) if consumes <= 2 else ()

        def policy(_batch: object) -> None:
            nonlocal policies
            policies += 1
            if policies == 1:
                raise RuntimeError("discard")
            recovered.set()

        application = self._application(
            1,
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                10,
                1,
                1,
                consume,
                policy,
            ),
        )

        with self.assertLogs(
            "kernel_design.executable_spec.assembly."
            "result_convergence_application",
            level="ERROR",
        ):
            application.start()
            self.assertTrue(recovered.wait(timeout=1))
            application.stop(timeout_millis=1_000)
        self.assertEqual(2, policies)

    def test_consume_exception_backs_off_only_its_lane(self) -> None:
        failed_lane_consumes = 0
        other_lane_finished = threading.Event()
        failed_lane_recovered = threading.Event()
        other_lane_consumed = False

        def failed_consumer(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal failed_lane_consumes
            failed_lane_consumes += 1
            if failed_lane_consumes == 1:
                raise RuntimeError("temporary consume failure")
            return (_report(),) if failed_lane_consumes == 2 else ()

        def other_consumer(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal other_lane_consumed
            if other_lane_consumed:
                return ()
            other_lane_consumed = True
            return (_report(),)

        application = self._application(
            2,
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                20,
                1,
                1,
                failed_consumer,
                lambda _batch: failed_lane_recovered.set(),
            ),
            _ResultLane(
                _ResultLaneId.TASK_FAILURE,
                100,
                20,
                1,
                1,
                other_consumer,
                lambda _batch: other_lane_finished.set(),
            ),
        )

        with self.assertLogs(
            "kernel_design.executable_spec.assembly."
            "result_convergence_application",
            level="ERROR",
        ):
            application.start()
            self.assertTrue(other_lane_finished.wait(timeout=1))
            self.assertTrue(failed_lane_recovered.wait(timeout=1))
            application.stop(timeout_millis=1_000)
        self.assertGreaterEqual(failed_lane_consumes, 2)

    def test_empty_lane_does_not_occupy_capacity(self) -> None:
        empty_consumes = 0
        other_finished = threading.Event()
        other_consumed = False

        def consume_empty(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal empty_consumes
            empty_consumes += 1
            return ()

        def consume_other(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal other_consumed
            if other_consumed:
                return ()
            other_consumed = True
            return (_report(),)

        application = self._application(
            1,
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                50,
                1,
                1,
                consume_empty,
                lambda _batch: None,
            ),
            _ResultLane(
                _ResultLaneId.TASK_FAILURE,
                100,
                50,
                1,
                1,
                consume_other,
                lambda _batch: other_finished.set(),
            ),
        )

        application.start()
        try:
            self.assertTrue(other_finished.wait(timeout=1))
            self.assertGreaterEqual(empty_consumes, 1)
            self.assertTrue(application.is_running())
        finally:
            application.stop(timeout_millis=1_000)

    def test_batch_thread_start_rejection_fails_application(self) -> None:
        consume_started = threading.Event()
        release_consume = threading.Event()

        def consume(_limit: int) -> tuple[DeliveryReport, ...]:
            consume_started.set()
            release_consume.wait(timeout=1)
            return (_report(),)

        application = self._application(
            1,
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                10,
                1,
                1,
                consume,
                lambda _batch: None,
            ),
        )
        application.start()
        self.assertTrue(consume_started.wait(timeout=1))

        with self.assertLogs(
            "kernel_design.executable_spec.assembly."
            "result_convergence_application",
            level="CRITICAL",
        ), patch(
            "kernel_design.executable_spec.assembly."
            "result_convergence_application.Thread.start",
            side_effect=RuntimeError("rejected"),
        ):
            release_consume.set()
            self._await(lambda: application.state == "FAILED")
        self.assertFalse(application.is_running())
        application.stop(timeout_millis=1_000)
        self.assertEqual("STOPPED", application.state)

    def test_stop_prevents_new_consume_while_waiting_for_batch(self) -> None:
        consumes = 0
        started = threading.Event()
        release = threading.Event()

        def consume(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal consumes
            consumes += 1
            return (_report(),)

        def block(_batch: object) -> None:
            started.set()
            release.wait(timeout=1)

        application = self._application(
            1,
            _ResultLane(
                _ResultLaneId.TASK_FAILURE,
                100,
                10,
                1,
                1,
                consume,
                block,
            ),
        )
        application.start()
        self.assertTrue(started.wait(timeout=1))

        stopped = threading.Event()
        stopper = threading.Thread(
            target=lambda: (
                application.stop(timeout_millis=1_000),
                stopped.set(),
            ),
        )
        stopper.start()
        self._await(lambda: application.state == "STOPPING")
        time.sleep(0.03)
        self.assertEqual(1, consumes)
        release.set()
        stopper.join(timeout=1)
        self.assertFalse(stopper.is_alive())
        self.assertTrue(stopped.is_set())
        self.assertEqual("STOPPED", application.state)

    def test_fatal_base_exception_fails_application(self) -> None:
        consumed = False

        def consume(_limit: int) -> tuple[DeliveryReport, ...]:
            nonlocal consumed
            if consumed:
                return ()
            consumed = True
            return (_report(),)

        application = self._application(
            1,
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                10,
                1,
                1,
                consume,
                lambda _batch: (_ for _ in ()).throw(KeyboardInterrupt()),
            ),
        )

        with self.assertLogs(
            "kernel_design.executable_spec.assembly."
            "result_convergence_application",
            level="CRITICAL",
        ):
            application.start()
            self._await(lambda: application.state == "FAILED")
        self.assertFalse(application.is_running())
        application.stop(timeout_millis=1_000)
        self.assertEqual("STOPPED", application.state)

    def test_capacity_validation_and_lifecycle(self) -> None:
        with self.assertRaises(ValueError):
            _ResultLane(
                _ResultLaneId.TASK_SUCCESS,
                100,
                10,
                2,
                1,
                lambda _limit: (),
                lambda _batch: None,
            )
        lane = _ResultLane(
            _ResultLaneId.TASK_SUCCESS,
            100,
            100,
            1,
            2,
            lambda _limit: (),
            lambda _batch: None,
        )
        with self.assertRaises(ValueError):
            self._application(1, lane)

        application = self._application(2, lane)
        application.start()
        with self.assertRaises(RuntimeError):
            application.start()
        application.stop(timeout_millis=1_000)
        application.stop(timeout_millis=1_000)

    @staticmethod
    def _application(
        global_max_concurrency: int,
        *lanes: _ResultLane,
    ) -> ResultConvergenceApplication:
        return ResultConvergenceApplication(
            lanes,
            global_max_concurrency=global_max_concurrency,
        )

    @staticmethod
    def _endless_lane(
        lane_id: _ResultLaneId,
        *,
        target: int,
        maximum: int,
        policies: _BlockingPolicies,
    ) -> _ResultLane:
        return _ResultLane(
            lane_id,
            100,
            20,
            target,
            maximum,
            lambda _limit: (_report(),),
            lambda _batch: policies.block(lane_id),
        )

    def _await(self, predicate: object) -> None:
        deadline = time.monotonic() + 1
        while not predicate() and time.monotonic() < deadline:  # type: ignore[operator]
            time.sleep(0.005)
        self.assertTrue(predicate())  # type: ignore[operator]


if __name__ == "__main__":
    unittest.main()
