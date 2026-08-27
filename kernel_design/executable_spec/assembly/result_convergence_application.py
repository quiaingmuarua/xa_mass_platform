from __future__ import annotations

import logging
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import Enum
from queue import Empty, Queue
from threading import Event, Lock, Thread, current_thread
from time import monotonic, monotonic_ns

from ..kernel.worker_delivery import DeliveryReport


_LOGGER = logging.getLogger(__name__)
_STOP = object()


class _ResultLaneId(Enum):
    TASK_SUCCESS = (0, "task-success")
    TASK_FAILURE = (1, "task-failure")
    ADAPTER_EVIDENCE = (2, "adapter-evidence")

    @property
    def priority(self) -> int:
        return self.value[0]


@dataclass(frozen=True, slots=True)
class _ResultLane:
    lane_id: _ResultLaneId
    batch_limit: int
    idle_poll_interval_millis: int
    target_concurrency: int
    max_concurrency: int
    consumer: Callable[[int], Sequence[DeliveryReport]]
    policy: Callable[[Sequence[DeliveryReport]], None]

    def __post_init__(self) -> None:
        if not isinstance(self.lane_id, _ResultLaneId):
            raise TypeError("result lane id must be _ResultLaneId")
        if not 1 <= self.batch_limit <= 100:
            raise ValueError("result lane batch limit must be between 1 and 100")
        if self.idle_poll_interval_millis <= 0:
            raise ValueError("result lane idle interval must be positive")
        if self.target_concurrency <= 0:
            raise ValueError("result lane target concurrency must be positive")
        if self.max_concurrency < self.target_concurrency:
            raise ValueError(
                "result lane max concurrency must be at least target concurrency"
            )
        if not callable(self.consumer) or not callable(self.policy):
            raise TypeError("result lane consumer and policy must be callable")


@dataclass(slots=True)
class _LaneRuntime:
    lane: _ResultLane
    inflight: int = 0
    next_eligible_nanos: int = 0


@dataclass(frozen=True, slots=True)
class _LaneCompletion:
    lane_id: _ResultLaneId
    batch_size: int
    failure: BaseException | None


class ResultConvergenceApplication:
    """Run fixed homogeneous Result lanes over shared weighted capacity."""

    def __init__(
        self,
        lanes: Sequence[_ResultLane],
        *,
        global_max_concurrency: int,
    ) -> None:
        if not lanes:
            raise ValueError("result convergence lanes must not be empty")
        if global_max_concurrency <= 0:
            raise ValueError(
                "result convergence global concurrency must be positive"
            )
        unique: dict[_ResultLaneId, _ResultLane] = {}
        for lane in lanes:
            if not isinstance(lane, _ResultLane):
                raise TypeError("result convergence lane is invalid")
            if lane.max_concurrency > global_max_concurrency:
                raise ValueError(
                    "result lane max concurrency exceeds global capacity: "
                    f"{lane.lane_id.name}"
                )
            if lane.lane_id in unique:
                raise ValueError(f"duplicate result lane: {lane.lane_id.name}")
            unique[lane.lane_id] = lane
        self._lanes = tuple(
            sorted(unique.values(), key=lambda lane: lane.lane_id.priority)
        )
        self._global_max_concurrency = global_max_concurrency
        self._lifecycle_lock = Lock()
        self._stop_event: Event | None = None
        self._completion_queue: Queue[object] | None = None
        self._dispatcher: Thread | None = None
        self._batch_threads: set[Thread] = set()
        self._state = "STOPPED"

    def start(self) -> None:
        with self._lifecycle_lock:
            if self._dispatcher is not None or self._state != "STOPPED":
                raise RuntimeError("result convergence is already started")
            stop_event = Event()
            completion_queue: Queue[object] = Queue()
            dispatcher = Thread(
                name="result-convergence-dispatcher",
                target=self._run_loop,
                kwargs={
                    "stop_event": stop_event,
                    "completion_queue": completion_queue,
                },
                daemon=False,
            )
            self._stop_event = stop_event
            self._completion_queue = completion_queue
            self._dispatcher = dispatcher
            self._state = "RUNNING"
            dispatcher.start()

    def stop(self, *, timeout_millis: int = 5_000) -> None:
        if timeout_millis <= 0:
            raise ValueError("stop timeout must be positive")
        with self._lifecycle_lock:
            dispatcher = self._dispatcher
            stop_event = self._stop_event
            completion_queue = self._completion_queue
            if dispatcher is None or stop_event is None or completion_queue is None:
                return
            self._state = "STOPPING"
            stop_event.set()
            completion_queue.put(_STOP)

        deadline = monotonic() + timeout_millis / 1_000
        dispatcher.join(timeout=max(0.001, deadline - monotonic()))
        if dispatcher.is_alive():
            self._mark_failed()
            raise TimeoutError("result convergence dispatcher did not stop")

        with self._lifecycle_lock:
            batch_threads = tuple(self._batch_threads)
        for thread in batch_threads:
            thread.join(timeout=max(0.001, deadline - monotonic()))
        if any(thread.is_alive() for thread in batch_threads):
            self._mark_failed()
            raise TimeoutError("result convergence batches did not stop")

        with self._lifecycle_lock:
            if self._dispatcher is dispatcher:
                self._dispatcher = None
                self._stop_event = None
                self._completion_queue = None
                self._batch_threads.clear()
                self._state = "STOPPED"

    def is_running(self) -> bool:
        with self._lifecycle_lock:
            self._refresh_dead_dispatcher()
            return (
                self._state == "RUNNING"
                and self._dispatcher is not None
                and self._dispatcher.is_alive()
            )

    @property
    def state(self) -> str:
        with self._lifecycle_lock:
            self._refresh_dead_dispatcher()
            return self._state

    def _run_loop(
        self,
        *,
        stop_event: Event,
        completion_queue: Queue[object],
    ) -> None:
        runtimes = {
            lane.lane_id: _LaneRuntime(lane=lane)
            for lane in self._lanes
        }
        global_inflight = 0
        stopping = False
        try:
            while not stop_event.is_set():
                global_inflight = self._drain_completions(
                    runtimes,
                    completion_queue,
                    global_inflight,
                )
                global_inflight = self._dispatch_available(
                    runtimes,
                    completion_queue,
                    stop_event,
                    global_inflight,
                )
                if stop_event.is_set():
                    stopping = True
                    break
                global_inflight = self._wait_for_work(
                    runtimes,
                    completion_queue,
                    global_inflight,
                )
            stopping = True
        except BaseException as failure:
            _LOGGER.critical(
                "result convergence dispatcher failed failure_type=%s",
                type(failure).__name__,
            )
        finally:
            with self._lifecycle_lock:
                if self._dispatcher is current_thread() and not stopping:
                    self._state = "FAILED"

    def _dispatch_available(
        self,
        runtimes: dict[_ResultLaneId, _LaneRuntime],
        completion_queue: Queue[object],
        stop_event: Event,
        global_inflight: int,
    ) -> int:
        while (
            not stop_event.is_set()
            and global_inflight < self._global_max_concurrency
        ):
            lane = self._select_eligible_lane(runtimes, monotonic_ns())
            if lane is None:
                break
            runtime = runtimes[lane.lane_id]
            try:
                batch = tuple(lane.consumer(lane.batch_limit))
            except Exception as failure:
                self._defer_lane(runtime)
                self._log_failure(lane, "consume", 0, failure)
                continue
            if not batch:
                self._defer_lane(runtime)
                continue
            runtime.inflight += 1
            global_inflight += 1
            thread = Thread(
                name=f"result-convergence-{lane.lane_id.value[1]}",
                target=self._execute_batch,
                args=(lane, batch, completion_queue),
                daemon=False,
            )
            with self._lifecycle_lock:
                self._batch_threads.add(thread)
            try:
                thread.start()
            except RuntimeError:
                with self._lifecycle_lock:
                    self._batch_threads.discard(thread)
                runtime.inflight -= 1
                global_inflight -= 1
                raise
        return global_inflight

    def _select_eligible_lane(
        self,
        runtimes: dict[_ResultLaneId, _LaneRuntime],
        now_nanos: int,
    ) -> _ResultLane | None:
        selected: _ResultLane | None = None
        for candidate in self._lanes:
            candidate_runtime = runtimes[candidate.lane_id]
            if (
                candidate_runtime.inflight >= candidate.max_concurrency
                or now_nanos < candidate_runtime.next_eligible_nanos
            ):
                continue
            if selected is None or self._preferred(
                candidate,
                candidate_runtime,
                selected,
                runtimes[selected.lane_id],
            ):
                selected = candidate
        return selected

    @staticmethod
    def _preferred(
        candidate: _ResultLane,
        candidate_runtime: _LaneRuntime,
        selected: _ResultLane,
        selected_runtime: _LaneRuntime,
    ) -> bool:
        candidate_ratio = (
            candidate_runtime.inflight * selected.target_concurrency
        )
        selected_ratio = (
            selected_runtime.inflight * candidate.target_concurrency
        )
        if candidate_ratio != selected_ratio:
            return candidate_ratio < selected_ratio
        return candidate.lane_id.priority < selected.lane_id.priority

    def _execute_batch(
        self,
        lane: _ResultLane,
        batch: tuple[DeliveryReport, ...],
        completion_queue: Queue[object],
    ) -> None:
        failure: BaseException | None = None
        try:
            lane.policy(batch)
        except BaseException as error:
            failure = error
        finally:
            completion_queue.put(
                _LaneCompletion(lane.lane_id, len(batch), failure)
            )
            with self._lifecycle_lock:
                self._batch_threads.discard(current_thread())

    def _drain_completions(
        self,
        runtimes: dict[_ResultLaneId, _LaneRuntime],
        completion_queue: Queue[object],
        global_inflight: int,
    ) -> int:
        while True:
            try:
                completion = completion_queue.get_nowait()
            except Empty:
                return global_inflight
            if completion is _STOP:
                continue
            assert isinstance(completion, _LaneCompletion)
            global_inflight = self._apply_completion(
                runtimes,
                completion,
                global_inflight,
            )

    def _apply_completion(
        self,
        runtimes: dict[_ResultLaneId, _LaneRuntime],
        completion: _LaneCompletion,
        global_inflight: int,
    ) -> int:
        runtime = runtimes[completion.lane_id]
        if runtime.inflight <= 0 or global_inflight <= 0:
            raise RuntimeError(
                f"unexpected result lane completion: {completion.lane_id.name}"
            )
        runtime.inflight -= 1
        global_inflight -= 1
        if completion.failure is None:
            return global_inflight
        if not isinstance(completion.failure, Exception):
            raise completion.failure
        self._defer_lane(runtime)
        self._log_failure(
            runtime.lane,
            "policy",
            completion.batch_size,
            completion.failure,
        )
        return global_inflight

    def _wait_for_work(
        self,
        runtimes: dict[_ResultLaneId, _LaneRuntime],
        completion_queue: Queue[object],
        global_inflight: int,
    ) -> int:
        if global_inflight >= self._global_max_concurrency:
            completion = completion_queue.get()
            if completion is _STOP:
                return global_inflight
            assert isinstance(completion, _LaneCompletion)
            return self._apply_completion(
                runtimes,
                completion,
                global_inflight,
            )
        now = monotonic_ns()
        waits = [
            max(0, runtime.next_eligible_nanos - now)
            for runtime in runtimes.values()
            if runtime.inflight < runtime.lane.max_concurrency
        ]
        timeout = None if not waits else min(waits) / 1_000_000_000
        if timeout == 0:
            return global_inflight
        try:
            completion = completion_queue.get(timeout=timeout)
        except Empty:
            return global_inflight
        if completion is _STOP:
            return global_inflight
        assert isinstance(completion, _LaneCompletion)
        return self._apply_completion(
            runtimes,
            completion,
            global_inflight,
        )

    def _defer_lane(self, runtime: _LaneRuntime) -> None:
        runtime.next_eligible_nanos = max(
            runtime.next_eligible_nanos,
            self._next_eligible(runtime.lane),
        )

    @staticmethod
    def _next_eligible(lane: _ResultLane) -> int:
        return monotonic_ns() + lane.idle_poll_interval_millis * 1_000_000

    @staticmethod
    def _log_failure(
        lane: _ResultLane,
        operation: str,
        batch_size: int,
        failure: Exception,
    ) -> None:
        _LOGGER.error(
            "result convergence %s failed lane=%s batch_size=%d failure_type=%s",
            operation,
            lane.lane_id.name,
            batch_size,
            type(failure).__name__,
        )

    def _mark_failed(self) -> None:
        with self._lifecycle_lock:
            self._state = "FAILED"

    def _refresh_dead_dispatcher(self) -> None:
        if (
            self._state == "RUNNING"
            and self._dispatcher is not None
            and not self._dispatcher.is_alive()
        ):
            self._state = "FAILED"
