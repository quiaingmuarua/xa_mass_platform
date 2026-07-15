from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from functools import partial
from threading import Event, Lock, Thread

from ..result_routing import ResultRoutingConfig, ResultRoutingPacer


_LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class ResultRoutingApplicationConfig:
    routing: ResultRoutingConfig
    interval_millis: int

    def __post_init__(self) -> None:
        if self.interval_millis <= 0:
            raise ValueError("result-routing interval must be positive")


class ResultRoutingApplication:
    """Application lifecycle for the bounded result-routing loop."""

    def __init__(self, pacer: ResultRoutingPacer) -> None:
        self.pacer = pacer
        self._lifecycle_lock = Lock()
        self._stop_event: Event | None = None
        self._thread: Thread | None = None

    def start(self, *, config: ResultRoutingApplicationConfig) -> None:
        with self._lifecycle_lock:
            if self._thread is not None:
                raise RuntimeError("result-routing application is already started")

            stop_event = Event()
            thread = Thread(
                name="result-routing",
                target=self._run_loop,
                kwargs={
                    "stop_event": stop_event,
                    "interval_millis": config.interval_millis,
                    "operation": partial(
                        self.pacer.route_seed_results,
                        config=config.routing,
                    ),
                },
                daemon=False,
            )
            thread.start()
            self._stop_event = stop_event
            self._thread = thread

    def stop(self, *, timeout_millis: int = 5_000) -> None:
        if timeout_millis <= 0:
            raise ValueError("stop timeout must be positive")

        with self._lifecycle_lock:
            thread = self._thread
            stop_event = self._stop_event
            if thread is None or stop_event is None:
                return
            stop_event.set()

        thread.join(timeout=timeout_millis / 1_000)
        if thread.is_alive():
            raise TimeoutError("result-routing loop did not stop")

        with self._lifecycle_lock:
            if self._thread is thread:
                self._thread = None
                self._stop_event = None

    @staticmethod
    def _run_loop(
        *,
        stop_event: Event,
        interval_millis: int,
        operation: Callable[[], int],
    ) -> None:
        while not stop_event.is_set():
            try:
                operation()
            except Exception:
                _LOGGER.exception("result-routing round failed")
            if stop_event.wait(interval_millis / 1_000):
                return
