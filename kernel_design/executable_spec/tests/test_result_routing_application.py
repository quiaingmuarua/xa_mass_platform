from __future__ import annotations

import unittest
from threading import Event
from unittest.mock import Mock, call

from kernel_design.executable_spec import ResultRoutingConfig, ResultRoutingPacer
from kernel_design.executable_spec.assembly.result_routing_application import (
    ResultRoutingApplication,
    ResultRoutingApplicationConfig,
)


class ResultRoutingApplicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.pacer = Mock(spec=ResultRoutingPacer)
        self.application = ResultRoutingApplication(self.pacer)
        self.config = ResultRoutingApplicationConfig(
            routing=ResultRoutingConfig(
                per_result_class_batch_limit=100,
            ),
            interval_millis=1_000,
        )

    def tearDown(self) -> None:
        self.application.stop(timeout_millis=1_000)

    def test_start_runs_pacer_and_stop_allows_restart(self) -> None:
        routed = Event()
        self.pacer.route_worker_results.side_effect = lambda **_kwargs: routed.set() or 0

        self.application.start(config=self.config)
        self.assertTrue(routed.wait(timeout=1))
        with self.assertRaises(RuntimeError):
            self.application.start(config=self.config)
        self.application.stop(timeout_millis=1_000)

        self.assertEqual(
            [call(config=self.config.routing)],
            self.pacer.route_worker_results.call_args_list,
        )
        self.application.start(config=self.config)
        self.application.stop(timeout_millis=1_000)

    def test_failed_round_is_logged_and_loop_continues(self) -> None:
        recovered = Event()
        call_count = 0

        def route(**_kwargs: object) -> int:
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                raise RuntimeError("temporary failure")
            recovered.set()
            return 0

        self.pacer.route_worker_results.side_effect = route
        config = ResultRoutingApplicationConfig(
            routing=self.config.routing,
            interval_millis=5,
        )

        with self.assertLogs(
            "kernel_design.executable_spec.assembly.result_routing_application",
            level="ERROR",
        ) as logs:
            self.application.start(config=config)
            self.assertTrue(recovered.wait(timeout=1))
            self.application.stop(timeout_millis=1_000)

        self.assertTrue(any("result-routing round failed" in row for row in logs.output))

    def test_config_rejects_non_positive_interval(self) -> None:
        with self.assertRaises(ValueError):
            ResultRoutingApplicationConfig(
                routing=self.config.routing,
                interval_millis=0,
            )


if __name__ == "__main__":
    unittest.main()
