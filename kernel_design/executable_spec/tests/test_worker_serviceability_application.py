from __future__ import annotations

import threading
import unittest

from kernel_design.executable_spec import (
    WorkerServiceabilityDispatchConfig,
)
from kernel_design.executable_spec.assembly.worker_serviceability_application import (
    WorkerServiceabilityDispatchApplication,
    WorkerServiceabilityDispatchApplicationConfig,
)


class FakeDispatchPacer:
    def __init__(self) -> None:
        self.called = threading.Event()
        self.calls = 0

    def dispatch_probes(self, *, config: object) -> int:
        self.calls += 1
        self.called.set()
        return 0


class WorkerServiceabilityApplicationTest(unittest.TestCase):
    def test_dispatch_owns_one_non_daemon_loop(self) -> None:
        dispatch_pacer = FakeDispatchPacer()
        dispatch = WorkerServiceabilityDispatchApplication(dispatch_pacer)
        dispatch_config = WorkerServiceabilityDispatchApplicationConfig(
            dispatch=WorkerServiceabilityDispatchConfig(),
            interval_millis=10,
        )

        dispatch.start(config=dispatch_config)
        self.assertTrue(dispatch_pacer.called.wait(1))
        self.assertIsNotNone(dispatch._thread)
        self.assertFalse(dispatch._thread.daemon)

        dispatch.stop(timeout_millis=1_000)
        dispatch.stop(timeout_millis=1_000)

    def test_repeated_start_is_rejected(self) -> None:
        pacer = FakeDispatchPacer()
        application = WorkerServiceabilityDispatchApplication(pacer)
        config = WorkerServiceabilityDispatchApplicationConfig(
            dispatch=WorkerServiceabilityDispatchConfig(),
            interval_millis=10,
        )
        application.start(config=config)
        self.assertTrue(pacer.called.wait(1))
        try:
            with self.assertRaises(RuntimeError):
                application.start(config=config)
        finally:
            application.stop(timeout_millis=1_000)

    def test_application_configs_reject_non_positive_intervals(self) -> None:
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchApplicationConfig(
                dispatch=WorkerServiceabilityDispatchConfig(),
                interval_millis=0,
            )


if __name__ == "__main__":
    unittest.main()
