from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from kernel_design.executable_spec.assembly import KernelApplication
from kernel_design.executable_spec.assembly.__main__ import _run_application


class _ReadyObservingInput(io.BytesIO):
    def __init__(self, ready_file: Path, token: str) -> None:
        super().__init__(b"")
        self._ready_file = ready_file
        self._token = token

    def read(self, size: int = -1) -> bytes:
        if self._ready_file.read_text(encoding="utf-8") != self._token:
            raise AssertionError("ready file was not published before blocking")
        return super().read(size)


class KernelApplicationCliTest(unittest.TestCase):
    def test_production_cli_has_no_python_network_host(self) -> None:
        kernel_root = Path(__file__).parents[2]
        self.assertEqual(
            list((kernel_root / "runtime_server").glob("*.py")),
            [],
        )
        self.assertEqual(
            (kernel_root / "requirements.txt")
            .read_text(encoding="utf-8")
            .splitlines(),
            ["redis"],
        )
        cli_source = (
            kernel_root / "executable_spec" / "assembly" / "__main__.py"
        ).read_text(encoding="utf-8")
        for forbidden in ("fastapi", "uvicorn", "http.server", "socket"):
            self.assertNotIn(forbidden, cli_source.lower())

    def test_managed_run_publishes_ready_and_stops_on_stdin_eof(self) -> None:
        application = Mock(spec=KernelApplication)
        token = "instance-1"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "kernel.json"
            ready = root / "state" / "ready"
            config.write_text("{}", encoding="utf-8")

            _run_application(
                config_path=config,
                instance_token=token,
                ready_file=ready,
                input_stream=_ReadyObservingInput(ready, token),
                application_factory=lambda _config: application,
            )

            self.assertFalse(ready.exists())
        application.start.assert_called_once_with()
        application.stop.assert_called_once_with()

    def test_start_failure_does_not_publish_ready_or_call_stop(self) -> None:
        application = Mock(spec=KernelApplication)
        application.start.side_effect = RuntimeError("start failed")
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory) / "ready"
            with self.assertRaisesRegex(RuntimeError, "start failed"):
                _run_application(
                    config_path=None,
                    instance_token="instance-2",
                    ready_file=ready,
                    input_stream=io.BytesIO(),
                    application_factory=lambda _config: application,
                )
            self.assertFalse(ready.exists())
        application.stop.assert_not_called()

    def test_direct_cli_mode_needs_no_parent_protocol(self) -> None:
        application = Mock(spec=KernelApplication)
        _run_application(
            config_path=None,
            instance_token=None,
            ready_file=None,
            input_stream=io.BytesIO(),
            application_factory=lambda _config: application,
        )
        application.start.assert_called_once_with()
        application.stop.assert_called_once_with()

    def test_parent_protocol_arguments_are_atomic(self) -> None:
        with self.assertRaisesRegex(ValueError, "provided together"):
            _run_application(
                config_path=None,
                instance_token="instance-3",
                ready_file=None,
                input_stream=io.BytesIO(),
            )


if __name__ == "__main__":
    unittest.main()
