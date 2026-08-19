from __future__ import annotations

import threading
import unittest

from kernel_design.executable_spec import (
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityResultConfig,
)
from kernel_design.executable_spec.assembly.worker_serviceability_application import (
    WorkerServiceabilityDispatchApplication,
    WorkerServiceabilityDispatchApplicationConfig,
    AdapterEvidenceResultApplication,
    AdapterEvidenceResultApplicationConfig,
)


class FakeDispatchPacer:
    def __init__(self) -> None:
        self.called = threading.Event()
        self.calls = 0

    def dispatch_probes(self, *, config: object) -> int:
        self.calls += 1
        self.called.set()
        return 0


class FakeResultPacer:
    def __init__(self) -> None:
        self.called = threading.Event()
        self.calls = 0

    def route_adapter_evidence(self, *, config: object) -> int:
        self.calls += 1
        self.called.set()
        return 0


class WorkerServiceabilityApplicationTest(unittest.TestCase):
    def test_dispatch_and_result_each_own_one_non_daemon_loop(self) -> None:
        dispatch_pacer = FakeDispatchPacer()
        result_pacer = FakeResultPacer()
        dispatch = WorkerServiceabilityDispatchApplication(dispatch_pacer)
        result = AdapterEvidenceResultApplication(result_pacer)
        dispatch_config = WorkerServiceabilityDispatchApplicationConfig(
            dispatch=WorkerServiceabilityDispatchConfig(),
            interval_millis=10,
        )
        result_config = AdapterEvidenceResultApplicationConfig(
            result=WorkerServiceabilityResultConfig(),
            interval_millis=10,
        )

        result.start(config=result_config)
        dispatch.start(config=dispatch_config)
        self.assertTrue(result_pacer.called.wait(1))
        self.assertTrue(dispatch_pacer.called.wait(1))
        self.assertIsNotNone(result._thread)
        self.assertIsNotNone(dispatch._thread)
        self.assertFalse(result._thread.daemon)
        self.assertFalse(dispatch._thread.daemon)

        dispatch.stop(timeout_millis=1_000)
        result.stop(timeout_millis=1_000)
        dispatch.stop(timeout_millis=1_000)
        result.stop(timeout_millis=1_000)

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
        with self.assertRaises(ValueError):
            AdapterEvidenceResultApplicationConfig(
                result=WorkerServiceabilityResultConfig(),
                interval_millis=0,
            )


if __name__ == "__main__":
    unittest.main()
