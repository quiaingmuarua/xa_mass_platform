from __future__ import annotations

import inspect
import json
import os
import time
import unittest
import uuid
from dataclasses import fields, replace
from unittest.mock import Mock, patch

import kernel_design.executable_spec as executable_spec_package
import kernel_design.executable_spec.assembly as assembly_package
from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    WorkerScoreCore,
)

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec.assembly import (
    TaskType,
    WorkerCommandConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCloseResult,
    TaskCloseStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendStatus,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    DeliveryEndpoint,
    WorkerRuntimeStatus,
)
from kernel_design.executable_spec.assembly._redis_process import _RedisKernelProcess
from kernel_design.executable_spec.kernel import (
    TaskResourceCatalog,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
)


class KernelApplicationConfigTest(unittest.TestCase):
    def test_zero_config_uses_internal_defaults(self) -> None:
        expected = KernelApplicationConfig(
            redis_url="redis://localhost:6379/15",
            redis_prefix="default",
            worker_allocation_interval_millis=100,
            running_activation_interval_millis=100,
            task_dispatch_interval_millis=100,
            result_routing_interval_millis=100,
            running_task_soft_limit=100,
            stop_timeout_millis=5_000,
        )

        self.assertEqual(expected, KernelApplicationConfig.from_json())
        self.assertEqual(expected, KernelApplicationConfig.from_json("{}"))
        self.assertIsNone(expected.worker_serviceability)

    def test_partial_json_overrides_only_public_process_coordinates(self) -> None:
        config = KernelApplicationConfig.from_json(
            json.dumps(
                {
                    "redis": {"url": "redis://redis:6379/1", "prefix": "demo"},
                    "assignmentDispatch": {
                        "taskDispatchIntervalMillis": 250,
                    },
                    "systemPolicy": {"runningTaskSoftLimit": 50},
                    "resultRouting": {"intervalMillis": 300},
                    "stopTimeoutMillis": 2_000,
                }
            ),
        )

        self.assertEqual("redis://redis:6379/1", config.redis_url)
        self.assertEqual("demo", config.redis_prefix)
        self.assertEqual(100, config.worker_allocation_interval_millis)
        self.assertEqual(100, config.running_activation_interval_millis)
        self.assertEqual(250, config.task_dispatch_interval_millis)
        self.assertEqual(300, config.result_routing_interval_millis)
        self.assertEqual(50, config.running_task_soft_limit)
        self.assertEqual(2_000, config.stop_timeout_millis)

    def test_serviceability_is_optional_and_strictly_bounded(self) -> None:
        config = KernelApplicationConfig.from_json(
            json.dumps(
                {
                    "workerServiceability": {
                        "taskScanLimit": 42,
                        "dispatchIntervalMillis": 2_000,
                        "resultIntervalMillis": 200,
                        "recoveryRetryIntervalMillis": 120_000,
                        "probeSweepRestartDelayMillis": 20_000,
                        "maxRecoveryAttempts": 4,
                        "hotScanLimit": 70,
                        "recoveryScanLimit": 30,
                        "resultReportLimit": 20,
                        "evidenceMaxAgeMillis": 45_000,
                        "probeExcludedEndpointManagerIds": [
                            "system-polling",
                            "legacy-polling",
                        ],
                    }
                }
            )
        )

        self.assertIsNotNone(config.worker_serviceability)
        serviceability = config.worker_serviceability
        assert serviceability is not None
        self.assertEqual(42, serviceability.task_scan_limit)
        self.assertEqual(2_000, serviceability.dispatch_interval_millis)
        self.assertEqual(200, serviceability.result_interval_millis)
        self.assertEqual(
            20_000,
            serviceability.probe_sweep_restart_delay_millis,
        )
        self.assertEqual(4, serviceability.max_recovery_attempts)
        self.assertEqual(45_000, serviceability.evidence_max_age_millis)
        self.assertEqual(
            ("system-polling", "legacy-polling"),
            serviceability.probe_excluded_endpoint_manager_ids,
        )

        defaults = KernelApplicationConfig.from_json(
            '{"workerServiceability":{}}'
        ).worker_serviceability
        assert defaults is not None
        self.assertEqual(10_000, defaults.probe_sweep_restart_delay_millis)
        self.assertEqual(
            ("system-polling",),
            defaults.probe_excluded_endpoint_manager_ids,
        )
        no_exclusions = KernelApplicationConfig.from_json(
            '{"workerServiceability":{'
            '"probeExcludedEndpointManagerIds":[]}}'
        ).worker_serviceability
        assert no_exclusions is not None
        self.assertEqual((), no_exclusions.probe_excluded_endpoint_manager_ids)

    def test_serviceability_mints_one_aligned_internal_hot_floor(self) -> None:
        enabled = KernelApplicationConfig.from_json(
            '{"workerServiceability":{}}'
        )
        internal = KernelApplication._internal_process_config(enabled)
        self.assertIsNotNone(internal.hot_eligibility_floor_millis)
        assert internal.hot_eligibility_floor_millis is not None
        self.assertEqual(
            0,
            internal.hot_eligibility_floor_millis
            % WorkerScoreCore.SLOT_MILLIS,
        )
        self.assertIsNone(
            KernelApplication._internal_process_config(
                KernelApplicationConfig()
            ).hot_eligibility_floor_millis
        )

        invalid_configs = (
            '{"workerServiceability": {"workerGroupIds": ["a"]}}',
            '{"workerServiceability": {"taskScanLimit": 0}}',
            '{"workerServiceability": {"taskScanLimit": 101}}',
            '{"workerServiceability": {"taskScanLimit": true}}',
            '{"workerServiceability": {"unknown": 1}}',
            '{"workerServiceability": {'
            '"hotScanLimit": 81, "recoveryScanLimit": 20}}',
            '{"workerServiceability": {'
            '"staleHotAfterMillis": 1}}',
            '{"workerServiceability": {'
            '"probeExcludedEndpointManagerIds": ["x", "x"]}}',
            '{"workerServiceability": {'
            '"probeSweepRestartDelayMillis": 0}}',
        )
        for config_json in invalid_configs:
            with self.subTest(config_json=config_json), self.assertRaises(ValueError):
                KernelApplicationConfig.from_json(config_json)

    def test_unknown_malformed_and_non_positive_values_are_rejected(self) -> None:
        invalid_configs = (
            "",
            "[]",
            "{bad-json",
            '{"unknown": 1}',
            '{"redis": {"host": "localhost"}}',
            '{"redis": {"url": ""}}',
            '{"stopTimeoutMillis": 0}',
            '{"assignmentDispatch": {"workerAllocationIntervalMillis": -1}}',
            '{"assignmentDispatch": {"runningActivationIntervalMillis": true}}',
            '{"assignmentDispatch": {"task'
            'ItemDispatchIntervalMillis": 100}}',
            '{"resultRouting": {"intervalMillis": 0}}',
            '{"resultRouting": {"batchLimit": 100}}',
            '{"systemPolicy": {"runningTaskSoftLimit": 0}}',
            '{"systemPolicy": {"runningTaskSoftLimit": true}}',
            '{"systemPolicy": {"fairness": "weighted"}}',
            '{"workerPropertyIndexes": {}}',
        )
        for config_json in invalid_configs:
            with self.subTest(config_json=config_json), self.assertRaises(ValueError):
                KernelApplicationConfig.from_json(config_json)

    def test_public_config_exposes_only_process_and_system_policy_fields(self) -> None:
        self.assertEqual(
            [
                "redis_url",
                "redis_prefix",
                "worker_allocation_interval_millis",
                "running_activation_interval_millis",
                "task_dispatch_interval_millis",
                "result_routing_interval_millis",
                "running_task_soft_limit",
                "stop_timeout_millis",
                "worker_serviceability",
            ],
            [field.name for field in fields(KernelApplicationConfig)],
        )


class KernelApplicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.process = self._process_mock()
        self.process_patch = patch.object(
            _RedisKernelProcess,
            "from_url",
            return_value=self.process,
        )
        self.from_url = self.process_patch.start()
        self.application = KernelApplication()

    def tearDown(self) -> None:
        if self.application._started:
            self.application.stop()
        self.process_patch.stop()

    def test_public_surface_hides_runtime_score_and_internal_config(self) -> None:
        public_methods = {
            name
            for name, method in inspect.getmembers(
                KernelApplication,
                predicate=inspect.isfunction,
            )
            if not name.startswith("_")
        }
        self.assertEqual(
            {
                "approve_task",
                "close_task",
                "create_task",
                "start",
                "stop",
                "wake_task_dispatch",
            },
            public_methods,
        )
        self.assertFalse(hasattr(self.application, "task_runtime"))
        self.assertFalse(hasattr(self.application, "worker_runtime"))
        self.assertFalse(hasattr(self.application, "task_score"))
        self.assertFalse(hasattr(self.application, "worker_score"))
        self.assertFalse(hasattr(self.application, "pacer"))
        self.assertNotIn(
            "suffix",
            inspect.signature(KernelApplication.create_task).parameters,
        )
        self.assertFalse(hasattr(self.application, "register_worker"))
        self.assertFalse(hasattr(self.application, "register_worker_group"))
        for package in (assembly_package, executable_spec_package):
            self.assertFalse(hasattr(package, "RedisKernelProcess"))
            self.assertFalse(hasattr(package, "RedisKernelProcessConfig"))
        self.assertFalse(hasattr(assembly_package, "AssignmentDispatchApplication"))

    def test_constructs_one_internal_process_from_resolved_config(self) -> None:
        self.from_url.assert_called_once()
        call = self.from_url.call_args
        self.assertEqual("redis://localhost:6379/15", call.kwargs["redis_url"])
        internal = call.kwargs["config"]
        self.assertEqual("default", internal.prefix)
        self.assertEqual(100, internal.assignment_dispatch.worker_allocation.task_batch_limit)

        self.assertEqual(100, internal.worker_candidate_scan_limit)
        self.assertEqual(
            5_000,
            internal.assignment_dispatch.worker_allocation.worker_lease_duration_millis,
        )
        self.assertEqual(
            5,
            internal.assignment_dispatch.task_dispatch.max_empty_recheck_times,
        )
        self.assertEqual(
            1_000,
            internal.assignment_dispatch.task_dispatch.empty_recheck_interval_millis,
        )
        self.assertEqual(
            1_000,
            internal.assignment_dispatch.running_activation.priority_recheck_step_millis,
        )
        self.assertEqual(
            100,
            internal.result_routing.routing.per_outcome_batch_limit,
        )
        self.assertEqual(100, internal.result_routing.interval_millis)
        self.assertIsNone(internal.worker_serviceability_dispatch)
        self.assertIsNone(internal.worker_serviceability_result)

    def test_constructs_enabled_serviceability_process_configs(self) -> None:
        config = KernelApplicationConfig.from_json(
            '{"workerServiceability":{"taskScanLimit":37}}'
        )

        KernelApplication(config)

        call = self.from_url.call_args
        internal = call.kwargs["config"]
        self.assertEqual(
            37,
            internal.worker_serviceability_dispatch.dispatch.task_scan_limit,
        )
        self.assertEqual(
            1_000,
            internal.worker_serviceability_dispatch.interval_millis,
        )
        self.assertEqual(
            10_000,
            internal.worker_serviceability_dispatch.dispatch
            .probe_sweep_restart_delay_millis,
        )
        self.assertEqual(
            5,
            internal.worker_serviceability_result.result.max_recovery_attempts,
        )
        self.assertEqual(
            30_000,
            internal.worker_serviceability_result.result.evidence_max_age_millis,
        )
        self.assertEqual(
            100,
            internal.worker_serviceability_result.interval_millis,
        )

    def test_dispatch_wake_is_a_bounded_application_hint(self) -> None:
        self.process._task_dispatch_wake_inbox.offer.return_value = 2
        self.application.start()

        self.assertEqual(
            2,
            self.application.wake_task_dispatch(
                task_ids=("task-1", "task-2"),
            ),
        )
        self.process._task_dispatch_wake_inbox.offer.assert_called_once_with(
            task_ids=("task-1", "task-2"),
        )

    def test_commands_require_successful_start_and_lifecycle_is_strict(self) -> None:
        with self.assertRaises(RuntimeError):
            self.application.create_task(descriptor=self._task_descriptor())

        self.application.start()
        self.process.start.assert_called_once_with()
        with self.assertRaises(RuntimeError):
            self.application.start()

        self.application.stop()
        self.application.stop()
        self.process.stop.assert_called_once_with()

    def test_failed_start_keeps_commands_closed(self) -> None:
        self.process.start.side_effect = RuntimeError("Redis unavailable")

        with self.assertRaisesRegex(RuntimeError, "Redis unavailable"):
            self.application.start()
        with self.assertRaisesRegex(RuntimeError, "not started"):
            self.application.create_task(descriptor=self._task_descriptor())

    def test_stop_timeout_keeps_application_started_for_retry(self) -> None:
        self.application.start()
        self.process.stop.side_effect = (TimeoutError("blocked round"), None)

        with self.assertRaisesRegex(TimeoutError, "blocked round"):
            self.application.stop()
        self.assertTrue(self.application._started)

        self.application.stop()
        self.assertFalse(self.application._started)

    def test_create_task_hides_initial_suffix(self) -> None:
        task = self._task_descriptor()
        creation_result = TaskCreationResult(TaskCreationStatus.CREATED)
        self.process._task_runtime.create_task.return_value = creation_result
        self.application.start()

        self.assertIs(creation_result, self.application.create_task(descriptor=task))

        self.process._task_runtime.create_task.assert_called_once_with(
            descriptor=replace(task, empty_close_at_millis=0),
            suffix=1,
        )

    def test_create_task_resolves_item_driven_empty_close_once(self) -> None:
        task = self._task_descriptor(
            task_id="item-task",
            task_type=TaskType.ITEM_DRIVEN,
        )
        self.process._task_runtime.create_task.return_value = TaskCreationResult(
            TaskCreationStatus.CREATED
        )
        self.application.start()

        with patch(
            "kernel_design.executable_spec.assembly.application.time_ns",
            return_value=10_000_000_000,
        ):
            self.application.create_task(descriptor=task)

        persisted = self.process._task_runtime.create_task.call_args.kwargs[
            "descriptor"
        ]
        self.assertEqual(
            10_000 + 3 * 24 * 60 * 60 * 1_000,
            persisted.empty_close_at_millis,
        )

    def test_create_task_preserves_explicit_empty_close(self) -> None:
        task = replace(self._task_descriptor(), empty_close_at_millis=42_000)
        self.process._task_runtime.create_task.return_value = TaskCreationResult(
            TaskCreationStatus.CREATED
        )
        self.application.start()

        with patch(
            "kernel_design.executable_spec.assembly.application.time_ns"
        ) as current_time:
            self.application.create_task(descriptor=task)

        current_time.assert_not_called()
        self.process._task_runtime.create_task.assert_called_once_with(
            descriptor=task,
            suffix=1,
        )

    def test_approval_transitions_without_returning_score(self) -> None:
        task_id = "task-1"
        descriptor = self._task_descriptor(task_id)
        pre_review = TaskScoreState(
            task_id=task_id,
            score=300,
            band=TaskScoreBand.PRE_REVIEW,
            time_millis=1_000,
            suffix=1,
        )
        self.process._task_resource_catalog.load_task_allocation_descriptors.return_value = {
            task_id: descriptor
        }
        self.process._task_score.get_score_states.return_value = {
            task_id: pre_review
        }
        self.process._task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED,
            200,
        )
        self.application.start()

        with patch(
            "kernel_design.executable_spec.assembly.application.time_ns",
            return_value=2_000_000_000,
        ):
            result = self.application.approve_task(task_id=task_id)

        self.assertEqual(TaskApprovalResult(TaskApprovalStatus.APPROVED), result)
        self.assertEqual(["status", "reason"], [field.name for field in fields(result)])
        self.process._task_score.rewrite_score.assert_called_once_with(
            task_id=task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=2_000,
            target_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_suffix=80,
        )

    def test_approval_time_remains_newer_than_future_pre_review_coordinate(self) -> None:
        task_id = "task-future-review"
        descriptor = self._task_descriptor(task_id)
        self.process._task_resource_catalog.load_task_allocation_descriptors.return_value = {
            task_id: descriptor
        }
        self.process._task_score.get_score_states.return_value = {
            task_id: TaskScoreState(
                task_id=task_id,
                score=300,
                band=TaskScoreBand.PRE_REVIEW,
                time_millis=3_000,
                suffix=1,
            )
        }
        self.process._task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.TRANSITIONED
        )
        self.application.start()

        with patch(
            "kernel_design.executable_spec.assembly.application.time_ns",
            return_value=2_000_000_000,
        ):
            result = self.application.approve_task(task_id=task_id)

        self.assertEqual(TaskApprovalStatus.APPROVED, result.status)
        self.process._task_score.rewrite_score.assert_called_once_with(
            task_id=task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=3_000 + TaskScoreBandCore.SLOT_MILLIS,
            target_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_suffix=80,
        )

    def test_approval_is_idempotent_and_terminal_is_conflict(self) -> None:
        task_id = "task-1"
        self.process._task_resource_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._task_descriptor(task_id)
        }
        self.application.start()
        for band, expected in (
            (TaskScoreBand.ADMISSION_VISIBLE, TaskApprovalStatus.ALREADY_APPROVED),
            (TaskScoreBand.RUNNING_VISIBLE, TaskApprovalStatus.ALREADY_APPROVED),
            (TaskScoreBand.TERMINAL, TaskApprovalStatus.CONFLICT),
        ):
            with self.subTest(band=band):
                self.process._task_score.get_score_states.return_value = {
                    task_id: TaskScoreState(
                        task_id=task_id,
                        score=-1 if band == TaskScoreBand.TERMINAL else 100,
                        band=band,
                        time_millis=None if band == TaskScoreBand.TERMINAL else 1_000,
                        suffix=None if band == TaskScoreBand.TERMINAL else 0,
                    )
                }
                result = self.application.approve_task(task_id=task_id)
                self.assertEqual(expected, result.status)

        self.process._task_score.rewrite_score.assert_not_called()

    def test_approval_rejects_invalid_and_missing_tasks(self) -> None:
        self.application.start()

        invalid = self.application.approve_task(task_id="")
        self.process._task_resource_catalog.load_task_allocation_descriptors.return_value = {
            "missing": None
        }
        missing = self.application.approve_task(task_id="missing")

        self.assertEqual(TaskApprovalStatus.INVALID, invalid.status)
        self.assertEqual(TaskApprovalStatus.NOT_FOUND, missing.status)
        self.process._task_score.rewrite_score.assert_not_called()

    def test_stale_approval_reclassifies_concurrent_transition(self) -> None:
        task_id = "task-1"
        self.process._task_resource_catalog.load_task_allocation_descriptors.return_value = {
            task_id: self._task_descriptor(task_id)
        }
        self.process._task_score.get_score_states.side_effect = (
            {
                task_id: TaskScoreState(
                    task_id=task_id,
                    score=300,
                    band=TaskScoreBand.PRE_REVIEW,
                    time_millis=1_000,
                    suffix=1,
                )
            },
            {
                task_id: TaskScoreState(
                    task_id=task_id,
                    score=100,
                    band=TaskScoreBand.RUNNING_VISIBLE,
                    time_millis=1_100,
                    suffix=5,
                )
            },
        )
        self.process._task_score.rewrite_score.return_value = TaskScoreTransitionResult(
            TaskScoreTransitionStatus.STALE
        )
        self.application.start()

        result = self.application.approve_task(task_id=task_id)

        self.assertEqual(TaskApprovalStatus.ALREADY_APPROVED, result.status)

    def test_close_task_closes_any_positive_band_without_exposing_score(self) -> None:
        self.application.start()
        for band in (
            TaskScoreBand.PRE_REVIEW,
            TaskScoreBand.ADMISSION_VISIBLE,
            TaskScoreBand.RUNNING_VISIBLE,
        ):
            with self.subTest(band=band):
                task_id = f"task-{band.value}"
                self.process._task_score.get_score_states.return_value = {
                    task_id: TaskScoreState(
                        task_id=task_id,
                        score=100,
                        band=band,
                        time_millis=1_000,
                        suffix=0,
                    )
                }
                self.process._task_score.close_score.return_value = (
                    TaskScoreTransitionResult(
                        TaskScoreTransitionStatus.TRANSITIONED,
                        -1,
                    )
                )

                result = self.application.close_task(task_id=task_id)

                self.assertEqual(TaskCloseResult(TaskCloseStatus.CLOSED), result)
        self.assertNotIn(
            "terminal_score",
            inspect.signature(KernelApplication.close_task).parameters,
        )

    def test_close_task_is_idempotent_and_validates_identity(self) -> None:
        self.application.start()
        self.process._task_score.get_score_states.side_effect = (
            {"task-1": TaskScoreState("task-1", -1, TaskScoreBand.TERMINAL, None, None)},
            {"missing": None},
        )

        already_closed = self.application.close_task(task_id="task-1")
        missing = self.application.close_task(task_id="missing")
        invalid = self.application.close_task(task_id="")

        self.assertEqual(TaskCloseStatus.ALREADY_CLOSED, already_closed.status)
        self.assertEqual(TaskCloseStatus.NOT_FOUND, missing.status)
        self.assertEqual(TaskCloseStatus.INVALID, invalid.status)

    @staticmethod
    def _task_descriptor(
        task_id: str = "task-1",
        task_type: TaskType = TaskType.TASK_DRIVEN,
    ) -> TaskDescriptor:
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id="image-workers",
            task_type=task_type,
            allocation_rule=(
                {"worker.runtime": {"$eq": "python"}}
                if task_type is TaskType.TASK_DRIVEN
                else None
            ),
            config={
                "priority": "80",
                "maximumCandidateWorkers": "10",
                "maxRetryTimes": "3",
            },
        )

    @staticmethod
    def _process_mock() -> Mock:
        process = Mock()
        process._task_score = Mock(spec=TaskScoreBandCore)
        process._task_resource_catalog = Mock(spec=TaskResourceCatalog)
        process._task_runtime = Mock()
        process._task_dispatch_wake_inbox = Mock()
        process._worker_resource_catalog = Mock()
        return process


class RedisKernelProcessLifecycleTest(unittest.TestCase):
    def process(self) -> tuple[_RedisKernelProcess, list[str]]:
        process = _RedisKernelProcess.__new__(_RedisKernelProcess)
        process._redis = Mock()
        process._config = Mock(
            assignment_dispatch="assignment-config",
            result_routing="result-config",
            worker_serviceability_dispatch=None,
            worker_serviceability_result=None,
            stop_timeout_millis=123,
        )
        process._assignment_dispatch_application = Mock()
        process._result_routing_application = Mock()
        process._worker_serviceability_dispatch_application = None
        process._worker_serviceability_result_application = None
        order: list[str] = []
        process._result_routing_application.start.side_effect = (
            lambda **_kwargs: order.append("result-start")
        )
        process._assignment_dispatch_application.start.side_effect = (
            lambda **_kwargs: order.append("assignment-start")
        )
        process._assignment_dispatch_application.stop.side_effect = (
            lambda **_kwargs: order.append("assignment-stop")
        )
        process._result_routing_application.stop.side_effect = (
            lambda **_kwargs: order.append("result-stop")
        )
        return process, order

    def test_result_loop_starts_first_and_stops_last(self) -> None:
        process, order = self.process()

        process.start()
        process.stop()

        self.assertEqual(
            [
                "result-start",
                "assignment-start",
                "assignment-stop",
                "result-stop",
            ],
            order,
        )

    def test_assignment_start_failure_rolls_back_result_loop(self) -> None:
        process, order = self.process()

        def fail_assignment(**_kwargs: object) -> None:
            order.append("assignment-start")
            raise RuntimeError("start failed")

        process._assignment_dispatch_application.start.side_effect = fail_assignment

        with self.assertRaisesRegex(RuntimeError, "start failed"):
            process.start()

        self.assertEqual(
            ["result-start", "assignment-start", "result-stop"],
            order,
        )

    def test_serviceability_lifecycle_sits_between_result_and_assignment(self) -> None:
        process, order = self.process()
        process._config.worker_serviceability_result = "service-result-config"
        process._config.worker_serviceability_dispatch = "service-dispatch-config"
        process._worker_serviceability_result_application = Mock()
        process._worker_serviceability_dispatch_application = Mock()
        process._worker_serviceability_result_application.start.side_effect = (
            lambda **_kwargs: order.append("service-result-start")
        )
        process._worker_serviceability_dispatch_application.start.side_effect = (
            lambda **_kwargs: order.append("service-dispatch-start")
        )
        process._worker_serviceability_dispatch_application.stop.side_effect = (
            lambda **_kwargs: order.append("service-dispatch-stop")
        )
        process._worker_serviceability_result_application.stop.side_effect = (
            lambda **_kwargs: order.append("service-result-stop")
        )

        process.start()
        process.stop()

        self.assertEqual(
            [
                "result-start",
                "service-result-start",
                "service-dispatch-start",
                "assignment-start",
                "assignment-stop",
                "service-dispatch-stop",
                "service-result-stop",
                "result-stop",
            ],
            order,
        )


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run KernelApplication Redis proof",
)
class KernelApplicationIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        cls.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        try:
            cls.redis.ping()
        except redis_module.RedisError as error:
            raise unittest.SkipTest(f"real Redis is unavailable: {error}") from error

    def setUp(self) -> None:
        assert _REDIS_URL is not None
        self.prefix = f"application-{uuid.uuid4().hex}"
        self.config = KernelApplicationConfig(
            redis_url=_REDIS_URL,
            redis_prefix=self.prefix,
            worker_allocation_interval_millis=10,
            running_activation_interval_millis=10,
            task_dispatch_interval_millis=10,
            stop_timeout_millis=1_000,
        )
        self.resources_client = ResourcesCommandClient(self.config)
        self.worker_command_consumer = WorkerCommandConsumerClient(self.config)
        self.task_runtime = RedisTaskRuntime(
            self.redis,
            RedisTaskScoreBandCore(
                self.redis,
                score_key=f"tr:{self.prefix}:task:score",
            ),
            RedisTaskItemScoreBandCore(
                self.redis,
                prefix=self.prefix,
            ),
            prefix=self.prefix,
        )
        self.application = KernelApplication(self.config)

    def tearDown(self) -> None:
        self.application.stop()
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_control_application_and_task_runtime_reach_worker_command(self) -> None:
        worker_group_id = "image-workers"
        endpoint_manager_id = "endpoint-manager-1"
        task_id = "task-1"
        message_id = "message-1"

        group_result = self.resources_client.upsert_worker_group(
            descriptor=WorkerGroupDescriptor(
                worker_group_id=worker_group_id,
                attributes={"kind": "image"},
                event_codes=frozenset({"image.resize"}),
            )
        )
        worker_result = self.resources_client.upsert_worker(
            declaration=WorkerDeclaration(
                worker_id="worker-1",
                worker_group_id=worker_group_id,
                endpoint_manager_id=endpoint_manager_id,
                worker_properties={"runtime": "python"},
            )
        )
        self.application.start()

        created = self.application.create_task(
            descriptor=KernelApplicationTest._task_descriptor(task_id)
        )
        approved = self.application.approve_task(task_id=task_id)
        appended = self.task_runtime.append_items(
            task_id=task_id,
            items=(
                TaskItem(
                    message_id=message_id,
                    event_code="image.resize",
                    created_at_millis=int(time.time() * 1_000) - 1_000,
                    payload={"source": "input"},
                ),
            ),
        )

        deadline = time.monotonic() + 3
        command = None
        while time.monotonic() < deadline and command is None:
            command = self.worker_command_consumer.consume_worker_command(
                endpoint_manager_id=endpoint_manager_id,
                worker_id="worker-1",
            )
            if command is None:
                time.sleep(0.02)

        self.assertEqual(WorkerRuntimeStatus.OK, group_result.status)
        self.assertEqual(WorkerRuntimeStatus.OK, worker_result.status)
        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskApprovalStatus.APPROVED, approved.status)
        self.assertEqual(TaskItemAppendStatus.APPENDED, appended[message_id].status)
        self.assertIsNotNone(command)
        assert command is not None
        self.assertIs(command.src, DeliveryEndpoint.TASK)
        self.assertIs(command.dst, DeliveryEndpoint.WORKER)
        self.assertEqual("image.resize", command.message_type)
        self.assertEqual(
            message_id,
            json.loads(command.forward)["messageId"],
        )
        self.assertEqual(
            {"source": "input"},
            json.loads(command.payload),
        )

    def test_public_close_is_terminal_and_background_rounds_cannot_reopen(self) -> None:
        task_id = "task-close"
        self.application.start()
        created = self.application.create_task(
            descriptor=KernelApplicationTest._task_descriptor(task_id)
        )

        closed = self.application.close_task(task_id=task_id)
        time.sleep(0.2)
        stored_score = self.redis.zscore(
            f"tr:{self.prefix}:task:score",
            task_id,
        )
        closed_again = self.application.close_task(task_id=task_id)

        self.assertEqual(TaskCreationStatus.CREATED, created.status)
        self.assertEqual(TaskCloseStatus.CLOSED, closed.status)
        self.assertIsNotNone(stored_score)
        self.assertLess(int(stored_score), 0)
        self.assertEqual(TaskCloseStatus.ALREADY_CLOSED, closed_again.status)


if __name__ == "__main__":
    unittest.main()
