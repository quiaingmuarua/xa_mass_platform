from __future__ import annotations

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import MagicMock, patch


SCRIPT = Path(__file__).resolve().parents[2] / "run_local_runtime.py"
SPEC = importlib.util.spec_from_file_location("run_local_runtime", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class LocalRuntimeLauncherTest(unittest.TestCase):

    def test_profile_defaults_to_scenario_and_rejects_unknown_values(self) -> None:
        self.assertEqual(launcher.parse_arguments([]).profile, "scenario-workers")
        self.assertEqual(
            launcher.parse_arguments(["--profile", "agentforge"]).profile,
            "agentforge",
        )
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            launcher.parse_arguments(["--profile", "unknown"])

    def test_preview_options_are_rejected_for_other_profiles(self) -> None:
        for profile in ("scenario-workers", "agentforge"):
            for option in ("count", "seed", "port", "sandbox-root"):
                with self.subTest(profile=profile, option=option), redirect_stderr(io.StringIO()), \
                        self.assertRaises(SystemExit) as error, patch.object(launcher, "build_frontend") as build:
                    launcher.main(["--profile", profile, "--" + option, "3"])
                self.assertEqual(2, error.exception.code)
                build.assert_not_called()

    def test_preview_delegates_once_without_running_the_lab_build_or_processes(self) -> None:
        preview = MagicMock()
        preview.main.return_value = 0
        with patch.object(launcher, "load_preview", return_value=preview), \
                patch.object(launcher, "build_frontend") as frontend, \
                patch.object(launcher, "build_runtime_processes") as runtime, \
                patch.object(launcher, "start_server") as server:
            self.assertEqual(0, launcher.main(["--profile", "preview", "--count", "3", "--seed", "712",
                    "--port", "18600", "--sandbox-root", "existing/data/scenario-workers"]))
            preview.main.assert_called_once_with(["--build", "--count", "3", "--seed", "712",
                    "--port", "18600", "--sandbox-root", "existing/data/scenario-workers"])
            frontend.assert_not_called()
            runtime.assert_not_called()
            server.assert_not_called()
        preview.reset_mock()
        with patch.object(launcher, "load_preview", return_value=preview):
            self.assertEqual(0, launcher.main(["--profile", "preview"]))
            preview.main.assert_called_once_with(["--build"])

    def test_help_and_normal_profile_parsing_need_no_preview_dependencies(self) -> None:
        result = subprocess.run([sys.executable, "-S", str(SCRIPT), "--help"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("preview", result.stdout)
        with patch.object(launcher, "load_preview", side_effect=AssertionError("unexpected Preview import")):
            self.assertEqual("scenario-workers", launcher.parse_arguments([]).profile)
            self.assertEqual("agentforge", launcher.parse_arguments(["--profile", "agentforge"]).profile)

    def test_only_scenario_profile_builds_the_worker_host(self) -> None:
        self.assertEqual(
            launcher.gradle_tasks("scenario-workers"),
            [
                ":server_boot_jvm:bootJar",
                ":worker_simulator_jvm:installDist",
                ":distribution:server:installLocalPlatformDiagnosticCodes",
            ],
        )
        self.assertEqual(
            launcher.gradle_tasks("agentforge"),
            [
                ":server_boot_jvm:bootJar",
                ":distribution:server:installLocalPlatformDiagnosticCodes",
            ],
        )

    def test_profile_coordinates_follow_the_checked_configuration(self) -> None:
        self.assertEqual(
            launcher.runtime_api_base_url("scenario-workers", {}),
            "http://127.0.0.1:18082",
        )
        self.assertEqual(
            launcher.runtime_api_base_url(
                "agentforge",
                {"XA_MASS_AGENTFORGE_SERVER_PORT": "19182"},
            ),
            "http://127.0.0.1:19182",
        )
        self.assertEqual(
            launcher.runtime_api_base_url(
                "scenario-workers",
                {"SERVER_PORT": "19082"},
            ),
            "http://127.0.0.1:19082",
        )
        with self.assertRaisesRegex(RuntimeError, "1..65535"):
            launcher.runtime_api_base_url(
                "agentforge",
                {"XA_MASS_AGENTFORGE_SERVER_PORT": "70000"},
            )

    def test_server_command_owns_profile_and_absolute_frontend_path(self) -> None:
        process = MagicMock()
        with tempfile.TemporaryDirectory() as directory, patch.object(
            launcher,
            "FRONTEND_DIST",
            Path(directory) / "frontend/dist",
        ), patch.object(launcher.subprocess, "Popen", return_value=process) as popen:
            self.assertIs(
                launcher.start_server(
                    Path(directory) / "server.jar",
                    "scenario-workers",
                    {"PATH": "test-path"},
                ),
                process,
            )
        command = popen.call_args.args[0]
        self.assertIn("--spring.profiles.active=scenario-workers", command)
        static_argument = next(
            value
            for value in command
            if value.startswith("--spring.web.resources.static-locations=")
        )
        self.assertIn("frontend/dist/", static_argument.replace("\\", "/"))
        self.assertFalse(
            any("kernel-pacer" in value for value in command)
        )
        self.assertEqual(popen.call_args.kwargs["env"], {"PATH": "test-path"})

    def test_main_starts_worker_host_only_for_scenario_profile(self) -> None:
        server = MagicMock()
        worker_host = MagicMock()
        with patch.object(launcher, "build_frontend"), patch.object(
            launcher,
            "build_runtime_processes",
            return_value=(Path("server.jar"), [Path("host.jar")]),
        ), patch.object(
            launcher,
            "start_server",
            return_value=server,
        ), patch.object(launcher, "wait_for_server"), patch.object(
            launcher,
            "start_worker_host",
            return_value=worker_host,
        ) as start_host, patch.object(
            launcher,
            "supervise",
            return_value=0,
        ), patch.object(launcher, "stop_process"):
            self.assertEqual(launcher.main([], environ={}), 0)
            start_host.assert_called_once()
        with patch.object(launcher, "build_frontend"), patch.object(
            launcher,
            "build_runtime_processes",
            return_value=(Path("server.jar"), []),
        ), patch.object(
            launcher,
            "start_server",
            return_value=server,
        ), patch.object(launcher, "wait_for_server"), patch.object(
            launcher,
            "start_worker_host",
            return_value=worker_host,
        ) as start_host, patch.object(
            launcher,
            "supervise",
            return_value=0,
        ), patch.object(launcher, "stop_process"):
            self.assertEqual(
                launcher.main(["--profile", "agentforge"], environ={}),
                0,
            )
            start_host.assert_not_called()


if __name__ == "__main__":
    unittest.main()
