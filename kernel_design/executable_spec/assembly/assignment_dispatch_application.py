from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from functools import partial
from threading import Event, Lock, Thread
from time import monotonic

from ..assignment_dispatch import (
    TaskItemDispatchConfig,
    TaskItemDispatchPacer,
    TaskRunningActivationConfig,
    TaskRunningActivationPacer,
    TaskWorkerAllocationConfig,
    TaskWorkerAllocationPacer,
)


_LOGGER = logging.getLogger(__name__)
_RoundOperation = Callable[[], int]


@dataclass(frozen=True)
class AssignmentDispatchApplicationConfig:
    worker_allocation: TaskWorkerAllocationConfig
    running_activation: TaskRunningActivationConfig
    task_item_dispatch: TaskItemDispatchConfig
    worker_allocation_interval_millis: int
    running_activation_interval_millis: int
    task_item_dispatch_interval_millis: int

    def __post_init__(self) -> None:
        if any(
            interval_millis <= 0
            for interval_millis in (
                self.worker_allocation_interval_millis,
                self.running_activation_interval_millis,
                self.task_item_dispatch_interval_millis,
            )
        ):
            raise ValueError("assignment-dispatch intervals must be positive")


class AssignmentDispatchApplication:
    """Application lifecycle for independent assignment-dispatch pacers."""

    def __init__(
        self,
        worker_allocation_pacer: TaskWorkerAllocationPacer,
        running_activation_pacer: TaskRunningActivationPacer,
        task_item_dispatch_pacer: TaskItemDispatchPacer,
    ) -> None:
        self.worker_allocation_pacer = worker_allocation_pacer
        self.running_activation_pacer = running_activation_pacer
        self.task_item_dispatch_pacer = task_item_dispatch_pacer
        self._lifecycle_lock = Lock()
        self._stop_event: Event | None = None
        self._threads: tuple[Thread, ...] = ()

    def start(self, *, config: AssignmentDispatchApplicationConfig) -> None:
        """Start one sequential background loop for each pacer."""
        with self._lifecycle_lock:
            if self._threads:
                raise RuntimeError("assignment-dispatch application is already started")

            stop_event = Event()
            loops = (
                (
                    "worker-allocation",
                    config.worker_allocation_interval_millis,
                    partial(
                        self.worker_allocation_pacer.allocate_candidate_workers,
                        config=config.worker_allocation,
                    ),
                ),
                (
                    "running-activation",
                    config.running_activation_interval_millis,
                    partial(
                        self.running_activation_pacer.activate_running_visible_tasks,
                        config=config.running_activation,
                    ),
                ),
                (
                    "task-item-dispatch",
                    config.task_item_dispatch_interval_millis,
                    partial(
                        self.task_item_dispatch_pacer.dispatch_task_items,
                        config=config.task_item_dispatch,
                    ),
                ),
            )
            threads = tuple(
                Thread(
                    name=f"assignment-dispatch-{loop_name}",
                    target=self._run_loop,
                    kwargs={
                        "loop_name": loop_name,
                        "stop_event": stop_event,
                        "interval_millis": interval_millis,
                        "operation": operation,
                    },
                    daemon=False,
                )
                for loop_name, interval_millis, operation in loops
            )

            started_threads: list[Thread] = []
            try:
                for thread in threads:
                    thread.start()
                    started_threads.append(thread)
            except Exception:
                stop_event.set()
                for started_thread in started_threads:
                    started_thread.join()
                raise

            self._stop_event = stop_event
            self._threads = threads

    def stop(self, *, timeout_millis: int = 5_000) -> None:
        """Stop all loops and wait for any in-flight bounded round."""
        if timeout_millis <= 0:
            raise ValueError("stop timeout must be positive")

        with self._lifecycle_lock:
            threads = self._threads
            stop_event = self._stop_event
            if not threads or stop_event is None:
                return
            stop_event.set()

        deadline = monotonic() + timeout_millis / 1_000
        for thread in threads:
            thread.join(timeout=max(0.0, deadline - monotonic()))

        alive_threads = tuple(thread.name for thread in threads if thread.is_alive())
        if alive_threads:
            raise TimeoutError(
                "assignment-dispatch loops did not stop: "
                + ", ".join(alive_threads)
            )

        with self._lifecycle_lock:
            if self._threads == threads:
                self._threads = ()
                self._stop_event = None

    @staticmethod
    def _run_loop(
        *,
        loop_name: str,
        stop_event: Event,
        interval_millis: int,
        operation: _RoundOperation,
    ) -> None:
        while not stop_event.is_set():
            try:
                operation()
            except Exception:
                _LOGGER.exception("assignment-dispatch %s round failed", loop_name)
            if stop_event.wait(interval_millis / 1_000):
                return
