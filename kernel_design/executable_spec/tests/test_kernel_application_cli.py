from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from kernel_design.executable_spec.assembly import KernelApplication
from kernel_design.executable_spec.assembly.__main__ import _run_application


class KernelApplicationCliTest(unittest.TestCase):
    def test_oracle_cli_has_no_network_or_managed_production_protocol(self) -> None:
        kernel_root = Path(__file__).parents[2]
        self.assertEqual(list((kernel_root / "runtime_server").glob("*.py")), [])
        cli_source = (
            kernel_root / "executable_spec" / "assembly" / "__main__.py"
        ).read_text(encoding="utf-8")
        for forbidden in (
            "fastapi",
            "uvicorn",
            "http.server",
            "socket",
            "instance-token",
            "ready-file",
            "without-result-routing",
            "hot-eligibility-floor-millis",
        ):
            self.assertNotIn(forbidden, cli_source.lower())

    def test_oracle_run_starts_full_application_and_stops_on_stdin_eof(
        self,
    ) -> None:
        application = Mock(spec=KernelApplication)
        observed_configs = []
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "kernel.json"
            config.write_text(
                (
                    '{"redis":{"scope":"test_oracle_cli"},'
                    '"resultRouting":{"intervalMillis":17}}'
                ),
                encoding="utf-8",
            )
            _run_application(
                config_path=config,
                input_stream=io.BytesIO(),
                application_factory=lambda parsed: (
                    observed_configs.append(parsed) or application
                ),
            )

        self.assertEqual(1, len(observed_configs))
        self.assertEqual(
            17,
            observed_configs[0].result_routing_interval_millis,
        )
        application.start.assert_called_once_with()
        application.stop.assert_called_once_with()

    def test_oracle_run_requires_explicit_config(self) -> None:
        application_factory = Mock()

        with self.assertRaisesRegex(ValueError, "explicit --config"):
            _run_application(
                config_path=None,
                input_stream=io.BytesIO(),
                application_factory=application_factory,
            )

        application_factory.assert_not_called()

    def test_oracle_run_rejects_non_test_redis_scope(self) -> None:
        application_factory = Mock()
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "kernel.json"
            config.write_text(
                '{"redis":{"scope":"profile_default"}}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "test_\\*"):
                _run_application(
                    config_path=config,
                    input_stream=io.BytesIO(),
                    application_factory=application_factory,
                )

        application_factory.assert_not_called()

    def test_start_failure_does_not_call_stop(self) -> None:
        application = Mock(spec=KernelApplication)
        application.start.side_effect = RuntimeError("start failed")
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "kernel.json"
            config.write_text(
                '{"redis":{"scope":"test_oracle_start_failure"}}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "start failed"):
                _run_application(
                    config_path=config,
                    input_stream=io.BytesIO(),
                    application_factory=lambda _config: application,
                )

        application.stop.assert_not_called()


if __name__ == "__main__":
    unittest.main()
