from __future__ import annotations

import logging
from dataclasses import dataclass
from enum import Enum
from queue import Empty, Queue
from threading import Event, Lock, Thread, current_thread
from time import monotonic

from ..scheduling import (
    TaskDispatchConfig,
    TaskDispatchPolicy,
    TaskRunningActivationConfig,
    TaskRunningActivationPolicy,
    TaskSchedulingBatchSource,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPolicy,
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityDispatchPolicy,
)
from ..scheduling.task_scheduling_batch_source import DueTaskObservation


_LOGGER = logging.getLogger(__name__)
_TASK_BATCH_LIMIT = 100


@dataclass(frozen=True, slots=True)
class AssignmentDispatchConfig:
    worker_allocation: TaskWorkerAllocationConfig
    running_activation: TaskRunningActivationConfig
    task_dispatch: TaskDispatchConfig
    worker_allocation_interval_millis: int
    running_activation_interval_millis: int
    task_dispatch_interval_millis: int

    def __post_init__(self) -> None:
        if any(
            value <= 0
            for value in (
                self.worker_allocation_interval_millis,
                self.running_activation_interval_millis,
                self.task_dispatch_interval_millis,
            )
        ):
            raise ValueError("Dispatch lane intervals must be positive")


@dataclass(frozen=True, slots=True)
class WorkerServiceabilityDispatchLaneConfig:
    dispatch: WorkerServiceabilityDispatchConfig
    interval_millis: int

    def __post_init__(self) -> None:
        if self.interval_millis <= 0:
            raise ValueError("serviceability dispatch interval must be positive")


class _LaneId(Enum):
    RUNNING_ACTIVATION = "running-activation"
    WORKER_ALLOCATION = "worker-allocation"
    TASK_DISPATCH = "task-dispatch"
    WORKER_SERVICEABILITY = "worker-serviceability"


@dataclass(frozen=True, slots=True)
class _Lane:
    lane_id: _LaneId
    interval_millis: int
    policy: object


@dataclass(slots=True)
class _LaneRuntime:
    lane: _Lane
    inflight: bool = False
    next_eligible: float = 0.0


@dataclass(frozen=True, slots=True)
class _Completion:
    lane_id: _LaneId
    failure: BaseException | None


class DispatchConvergenceApplication:
    """Coordinate fixed Task-source dispatch lanes with single-flight batches."""

    def __init__(
        self,
        source: TaskSchedulingBatchSource,
        activation: TaskRunningActivationPolicy,
        allocation: TaskWorkerAllocationPolicy,
        dispatch: TaskDispatchPolicy,
        serviceability: WorkerServiceabilityDispatchPolicy | None,
    ) -> None:
        self.source = source
        self.activation = activation
        self.allocation = allocation
        self.dispatch = dispatch
        self.serviceability = serviceability
        self._lifecycle_lock = Lock()
        self._stop_event: Event | None = None
        self._coordinator: Thread | None = None
        self._batch_threads: set[Thread] = set()
        self._state = "STOPPED"

    @property
    def state(self) -> str:
        with self._lifecycle_lock:
            if (
                self._state == "RUNNING"
                and self._coordinator is not None
                and not self._coordinator.is_alive()
            ):
                self._state = "FAILED"
            return self._state

    def is_running(self) -> bool:
        return self.state == "RUNNING"

    def start(
        self,
        *,
        assignment: AssignmentDispatchConfig,
        serviceability: WorkerServiceabilityDispatchLaneConfig | None,
    ) -> None:
        with self._lifecycle_lock:
            if self._coordinator is not None or self._state != "STOPPED":
                raise RuntimeError("Dispatch Convergence is already started")
            stop_event = Event()
            completions: Queue[_Completion] = Queue()
            lanes = self._lanes(assignment, serviceability)
            coordinator = Thread(
                name="dispatch-convergence-coordinator",
                target=self._run,
                kwargs={
                    "stop_event": stop_event,
                    "completions": completions,
                    "lanes": lanes,
                },
                daemon=False,
            )
            self._stop_event = stop_event
            self._coordinator = coordinator
            self._state = "RUNNING"
            coordinator.start()

    def stop(self, *, timeout_millis: int) -> None:
        if timeout_millis <= 0:
            raise ValueError("stop timeout must be positive")
        with self._lifecycle_lock:
            coordinator = self._coordinator
            stop_event = self._stop_event
            if coordinator is None or stop_event is None:
                return
            self._state = "STOPPING"
            stop_event.set()
        deadline = monotonic() + timeout_millis / 1_000
        coordinator.join(timeout=max(0.0, deadline - monotonic()))
        if coordinator.is_alive():
            self._state = "FAILED"
            raise TimeoutError("Dispatch Convergence coordinator did not stop")
        for thread in tuple(self._batch_threads):
            thread.join(timeout=max(0.0, deadline - monotonic()))
        if any(thread.is_alive() for thread in self._batch_threads):
            self._state = "FAILED"
            raise TimeoutError("Dispatch Convergence batches did not stop")
        with self._lifecycle_lock:
            self._coordinator = None
            self._stop_event = None
            self._batch_threads.clear()
            self._state = "STOPPED"

    def _lanes(
        self,
        assignment: AssignmentDispatchConfig,
        serviceability: WorkerServiceabilityDispatchLaneConfig | None,
    ) -> tuple[_Lane, ...]:
        lanes = [
            _Lane(
                _LaneId.RUNNING_ACTIVATION,
                assignment.running_activation_interval_millis,
                lambda batch: self.activation.activate_running_visible_tasks(
                    batch,
                    config=assignment.running_activation,
                ),
            ),
            _Lane(
                _LaneId.WORKER_ALLOCATION,
                assignment.worker_allocation_interval_millis,
                lambda batch: self.allocation.allocate_candidate_workers(
                    batch,
                    config=assignment.worker_allocation,
                ),
            ),
            _Lane(
                _LaneId.TASK_DISPATCH,
                assignment.task_dispatch_interval_millis,
                lambda batch: self.dispatch.dispatch_tasks(
                    batch,
                    config=assignment.task_dispatch,
                ),
            ),
        ]
        if serviceability is not None:
            if self.serviceability is None:
                raise RuntimeError(
                    "serviceability lane requires its dispatch policy"
                )
            lanes.append(_Lane(
                _LaneId.WORKER_SERVICEABILITY,
                serviceability.interval_millis,
                lambda batch: self.serviceability.dispatch_probes(  # type: ignore[union-attr]
                    batch,
                    config=serviceability.dispatch,
                ),
            ))
        return tuple(lanes)

    def _run(
        self,
        *,
        stop_event: Event,
        completions: Queue[_Completion],
        lanes: tuple[_Lane, ...],
    ) -> None:
        runtimes = {
            lane.lane_id: _LaneRuntime(lane=lane)
            for lane in lanes
        }
        failed = False
        try:
            while not stop_event.is_set():
                self._drain_completions(runtimes, completions)
                self._dispatch_running(
                    runtimes,
                    completions,
                    stop_event,
                )
                self._dispatch_admission(
                    runtimes,
                    completions,
                    stop_event,
                )
                if stop_event.is_set():
                    break
                self._wait_for_work(runtimes, completions, stop_event)
        except BaseException:
            failed = True
            stop_event.set()
            _LOGGER.exception("Dispatch Convergence coordinator failed")
        finally:
            with self._lifecycle_lock:
                if self._coordinator is current_thread():
                    if failed and self._state != "STOPPING":
                        self._state = "FAILED"

    def _dispatch_running(
        self,
        runtimes: dict[_LaneId, _LaneRuntime],
        completions: Queue[_Completion],
        stop_event: Event,
    ) -> None:
        now = monotonic()
        eligible = tuple(
            runtime
            for lane_id, runtime in runtimes.items()
            if lane_id is not _LaneId.RUNNING_ACTIVATION
            and not runtime.inflight
            and now >= runtime.next_eligible
        )
        if not eligible or stop_event.is_set():
            return
        try:
            batch = self.source.acquire_running_tasks(limit=_TASK_BATCH_LIMIT)
        except Exception:
            _LOGGER.exception("Dispatch Convergence RUNNING source failed")
            for runtime in eligible:
                self._defer(runtime)
            return
        if not batch:
            for runtime in eligible:
                self._defer(runtime)
            return
        for runtime in eligible:
            self._submit(runtime, batch, completions)

    def _dispatch_admission(
        self,
        runtimes: dict[_LaneId, _LaneRuntime],
        completions: Queue[_Completion],
        stop_event: Event,
    ) -> None:
        runtime = runtimes[_LaneId.RUNNING_ACTIVATION]
        if (
            runtime.inflight
            or monotonic() < runtime.next_eligible
            or stop_event.is_set()
        ):
            return
        try:
            batch = self.source.acquire_admission_tasks(limit=_TASK_BATCH_LIMIT)
        except Exception:
            _LOGGER.exception("Dispatch Convergence ADMISSION source failed")
            self._defer(runtime)
            return
        if not batch:
            self._defer(runtime)
            return
        self._submit(runtime, batch, completions)

    def _submit(
        self,
        runtime: _LaneRuntime,
        batch: tuple[DueTaskObservation, ...],
        completions: Queue[_Completion],
    ) -> None:
        runtime.inflight = True
        thread = Thread(
            name=f"dispatch-convergence-{runtime.lane.lane_id.value}",
            target=self._execute_batch,
            args=(runtime.lane, batch, completions),
            daemon=False,
        )
        self._batch_threads.add(thread)
        try:
            thread.start()
        except BaseException:
            runtime.inflight = False
            self._batch_threads.discard(thread)
            raise

    @staticmethod
    def _execute_batch(
        lane: _Lane,
        batch: tuple[DueTaskObservation, ...],
        completions: Queue[_Completion],
    ) -> None:
        failure: BaseException | None = None
        try:
            lane.policy(batch)  # type: ignore[operator]
        except BaseException as error:
            failure = error
        finally:
            completions.put(_Completion(lane.lane_id, failure))

    def _drain_completions(
        self,
        runtimes: dict[_LaneId, _LaneRuntime],
        completions: Queue[_Completion],
    ) -> None:
        while True:
            try:
                completion = completions.get_nowait()
            except Empty:
                return
            self._apply_completion(runtimes, completion)

    def _apply_completion(
        self,
        runtimes: dict[_LaneId, _LaneRuntime],
        completion: _Completion,
    ) -> None:
        runtime = runtimes[completion.lane_id]
        runtime.inflight = False
        self._defer(runtime)
        self._batch_threads = {
            thread for thread in self._batch_threads if thread.is_alive()
        }
        if completion.failure is None:
            return
        if isinstance(completion.failure, Exception):
            _LOGGER.error(
                "Dispatch Convergence lane failed lane=%s type=%s",
                completion.lane_id.value,
                type(completion.failure).__name__,
            )
            return
        raise completion.failure

    def _wait_for_work(
        self,
        runtimes: dict[_LaneId, _LaneRuntime],
        completions: Queue[_Completion],
        stop_event: Event,
    ) -> None:
        now = monotonic()
        waits = tuple(
            max(0.0, runtime.next_eligible - now)
            for runtime in runtimes.values()
            if not runtime.inflight
        )
        timeout = min(waits, default=0.05)
        timeout = min(timeout, 0.05)
        try:
            completion = completions.get(timeout=timeout)
        except Empty:
            stop_event.wait(0)
            return
        self._apply_completion(runtimes, completion)

    @staticmethod
    def _defer(runtime: _LaneRuntime) -> None:
        runtime.next_eligible = (
            monotonic() + runtime.lane.interval_millis / 1_000
        )
