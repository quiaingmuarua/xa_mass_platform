from __future__ import annotations

import importlib.util
import io
import json
import os
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

    def test_android_messages_example_selects_existing_inputs_from_any_working_directory(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory(prefix="Android 本地 ") as directory:
            try:
                os.chdir(directory)
                args = launcher.parse_arguments(["--example", "android-messages"])
                command, port = launcher.consumer_arguments(args, {})
            finally:
                os.chdir(previous)
        self.assertEqual("consumer", args.profile)
        self.assertEqual(18082, port)
        self.assertIn("--spring.config.additional-location="
                      + (launcher.ROOT / "xa-android/config/messages-runtime.yaml").as_uri(), command)
        group_arg = next(value.split("=", 1)[1] for value in command
                         if value.startswith("--xa.mass.worker-assembly.group-config-json="))
        expected = json.loads((launcher.ROOT / "xa-android/config/worker-groups.json").read_text(encoding="utf-8"))
        self.assertEqual(expected, json.loads(group_arg))
        self.assertFalse(any("project-assembly" in value or "worker-matching" in value for value in command))

    def test_example_defaults_are_local_and_explicit_environment_values_win(self):
        for environment in ({}, {"XA_MASS_ADAPTER_PORT": "19183", "XA_MASS_REDIS_SCOPE": "custom_scope",
                                 "XA_MASS_TASK_ARCHIVE_DIRECTORY": "custom/archive", "SERVER_PORT": "19182"}):
            original = dict(environment)
            server = MagicMock()
            with self.subTest(environment=environment), patch.object(launcher, "build_frontend"), \
                    patch.object(launcher, "build_runtime_processes", return_value=Path("server.jar")) as build, \
                    patch.object(launcher, "start_server", return_value=server) as start, \
                    patch.object(launcher, "wait_for_server") as ready, \
                    patch.object(launcher, "supervise", return_value=0), patch.object(launcher, "stop_process") as stop:
                self.assertEqual(0, launcher.main(["--example", "android-messages"], environ=environment))
            self.assertEqual(original, environment)
            build.assert_called_once_with("consumer")
            values = start.call_args.args[2]
            self.assertEqual(environment.get("XA_MASS_ADAPTER_PORT", "18083"), values["XA_MASS_ADAPTER_PORT"])
            self.assertEqual(environment.get("XA_MASS_REDIS_SCOPE", "profile_android_messages_local"), values["XA_MASS_REDIS_SCOPE"])
            self.assertEqual(environment.get("XA_MASS_TASK_ARCHIVE_DIRECTORY",
                             str(launcher.ROOT / "data/android-messages/task-archives")), values["XA_MASS_TASK_ARCHIVE_DIRECTORY"])
            ready.assert_called_once_with(server, "http://127.0.0.1:" + environment.get("SERVER_PORT", "18082"))
            stop.assert_called_once_with(server, "XA Mass Server")

    def test_example_explicit_files_and_port_override_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "custom.yaml"; config.write_text("server.port: 19999")
            groups = root / "custom.json"; groups.write_text('{"custom-group":{}}')
            args = launcher.parse_arguments(["--example", "android-messages", "--runtime-config", str(config),
                                            "--groups", str(groups), "--server-port", "19182"])
            command, port = launcher.consumer_arguments(args, {"SERVER_PORT": "invalid"})
            self.assertEqual(19182, port)
            self.assertIn("--spring.config.additional-location=" + config.as_uri(), command)
            self.assertIn('--xa.mass.worker-assembly.group-config-json={"custom-group":{}}', command)
            config.unlink()
            with self.assertRaisesRegex(ValueError, "does not exist"):
                launcher.consumer_arguments(args, {})

    def test_example_rejects_incompatible_profile_and_ports_before_build(self):
        for profile in ("preview", "default"):
            with self.subTest(profile=profile), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                launcher.parse_arguments(["--profile", profile, "--example", "android-messages"])
        for environment in ({"XA_MASS_ADAPTER_PORT": "invalid"}, {"XA_MASS_ADAPTER_PORT": "18082"}):
            with self.subTest(environment=environment), redirect_stderr(io.StringIO()), \
                    patch.object(launcher, "build_frontend") as build:
                self.assertEqual(1, launcher.main(["--example", "android-messages"], environ=environment))
            build.assert_not_called()

    def test_consumer_options_reject_other_profiles_before_build(self):
        for profile in ("default", "preview"):
            for option, value in (("runtime-config", "runtime.yaml"), ("groups", "groups.json"), ("server-port", "19182")):
                with self.subTest(profile=profile, option=option), redirect_stderr(io.StringIO()), \
                        self.assertRaises(SystemExit), patch.object(launcher, "build_frontend") as build:
                    launcher.main(["--profile", profile, "--" + option, value])
                build.assert_not_called()
        for port in ("0", "65536", "invalid"):
            with self.subTest(port=port), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                launcher.parse_arguments(["--profile", "consumer", "--server-port", port])

    def test_external_files_are_validated_before_build_or_process_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            groups = Path(directory) / "groups.json"
            for document in ("[{}]", "{broken", ""):
                groups.write_text(document, encoding="utf-8")
                with self.subTest(document=document), redirect_stderr(io.StringIO()), \
                        patch.object(launcher, "build_frontend") as build, patch.object(launcher, "start_server") as start:
                    self.assertEqual(1, launcher.main(["--profile", "consumer", "--groups", str(groups)], environ={}))
                build.assert_not_called(); start.assert_not_called()
            for option in ("groups", "runtime-config"):
                with self.subTest(option=option), redirect_stderr(io.StringIO()), patch.object(launcher, "build_frontend") as build:
                    self.assertEqual(1, launcher.main(["--profile", "consumer", "--" + option, str(Path(directory) / "missing")], environ={}))
                build.assert_not_called()

    def test_external_startup_freezes_groups_and_uses_one_http_port(self):
        with tempfile.TemporaryDirectory(prefix="配置 ") as directory:
            root = Path(directory)
            config = root / "Runtime 配置.yaml"
            config.write_text("server.port: 19999\n", encoding="utf-8")
            groups = root / "Group 声明.json"
            document = {"external-only": {"eventCodes": ["extension.test"], "attributes": {"label": "样例"}}}
            groups.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
            server = MagicMock()
            with patch.object(launcher, "build_frontend"), patch.object(launcher, "build_runtime_processes", return_value=Path("server.jar")), \
                    patch.object(launcher, "start_server", return_value=server) as start, \
                    patch.object(launcher, "wait_for_server") as ready, patch.object(launcher, "supervise", return_value=0), \
                    patch.object(launcher, "stop_process") as stop:
                self.assertEqual(0, launcher.main(["--profile", "consumer", "--runtime-config", str(config),
                        "--groups", str(groups), "--server-port", "19182"], environ={"SERVER_PORT": "invalid-overridden-value"}))
            command = start.call_args.args[3]
            self.assertIn("--server.port=19182", command)
            self.assertIn("--spring.config.additional-location=" + config.resolve().as_uri(), command)
            group_arg = next(value.split("=", 1)[1] for value in command if value.startswith("--xa.mass.worker-assembly.group-config-json="))
            self.assertEqual(document, json.loads(group_arg))
            self.assertFalse(any("project-assembly" in value or "worker-matching" in value for value in command))
            ready.assert_called_once_with(server, "http://127.0.0.1:19182")
            stop.assert_called_once_with(server, "XA Mass Server")
            self.assertEqual("server.port: 19999\n", config.read_text(encoding="utf-8"))

    def test_consumer_defaults_do_not_infer_resource_files_and_readiness_failure_closes_process(self):
        args = launcher.parse_arguments(["--profile", "consumer"])
        self.assertEqual((["--server.port=18182"], 18182), launcher.consumer_arguments(args, {}))
        self.assertEqual((["--server.port=19182"], 19182), launcher.consumer_arguments(args, {"SERVER_PORT": "19182"}))
        server = MagicMock()
        with patch.object(launcher, "build_frontend"), patch.object(launcher, "build_runtime_processes", return_value=Path("server.jar")), \
                patch.object(launcher, "start_server", return_value=server), patch.object(launcher, "wait_for_server", side_effect=RuntimeError("not ready")), \
                patch.object(launcher, "stop_process") as stop, redirect_stderr(io.StringIO()):
            self.assertEqual(1, launcher.main(["--profile", "consumer"], environ={}))
        stop.assert_called_once_with(server, "XA Mass Server")

    def test_relative_inputs_resolve_from_calling_directory_not_checkout(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory(prefix="外部 working ") as directory:
            root = Path(directory)
            (root / "runtime.yaml").write_text("xa.mass.project-assembly.projects: []", encoding="utf-8")
            (root / "groups.json").write_text('{"external":{}}', encoding="utf-8")
            try:
                os.chdir(root)
                args = launcher.parse_arguments(["--profile", "consumer", "--runtime-config", "runtime.yaml", "--groups", "groups.json"])
                command, _ = launcher.consumer_arguments(args, {})
            finally:
                os.chdir(previous)
            self.assertIn("--spring.config.additional-location=" + (root / "runtime.yaml").as_uri(), command)
            self.assertIn('--xa.mass.worker-assembly.group-config-json={"external":{}}', command)

    def test_profile_defaults_to_preview_and_rejects_retired_values(self) -> None:
        self.assertEqual(launcher.parse_arguments([]).profile, "preview")
        self.assertEqual(
            launcher.parse_arguments(["--profile", "consumer"]).profile,
            "consumer",
        )
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            launcher.parse_arguments(["--profile", "unknown"])
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            launcher.parse_arguments(["--profile", "agentforge"])

    def test_preview_options_are_rejected_for_other_profiles(self) -> None:
        for profile in ("default", "consumer"):
            for option in ("count", "app-count", "seed", "port", "sandbox-root"):
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
            self.assertEqual(0, launcher.main(["--profile", "preview", "--count", "3", "--app-count", "0", "--seed", "712",
                    "--port", "18600", "--sandbox-root", "existing/data/scenario-workers"]))
            preview.main.assert_called_once_with(["--build", "--count", "3", "--app-count", "0", "--seed", "712",
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
            self.assertEqual("preview", launcher.parse_arguments([]).profile)
            self.assertEqual("consumer", launcher.parse_arguments(["--profile", "consumer"]).profile)

    def test_platform_profiles_build_no_worker_host(self) -> None:
        self.assertEqual(
            launcher.gradle_tasks("default"),
            [
                ":server_boot_jvm:bootJar",
                ":distribution:server:installLocalPlatformDiagnosticCodes",
            ],
        )
        self.assertEqual(
            launcher.gradle_tasks("consumer"),
            [
                ":server_boot_jvm:bootJar",
                ":distribution:server:installLocalPlatformDiagnosticCodes",
            ],
        )

    def test_profile_coordinates_follow_the_checked_configuration(self) -> None:
        self.assertEqual(
            launcher.runtime_api_base_url("default", {}),
            "http://127.0.0.1:18082",
        )
        self.assertEqual(
            launcher.runtime_api_base_url(
                "consumer",
                {"SERVER_PORT": "19182"},
            ),
            "http://127.0.0.1:19182",
        )
        self.assertEqual(
            launcher.runtime_api_base_url(
                "default",
                {"SERVER_PORT": "19082"},
            ),
            "http://127.0.0.1:19082",
        )
        with self.assertRaisesRegex(RuntimeError, "1..65535"):
            launcher.runtime_api_base_url(
                "consumer",
                {"SERVER_PORT": "70000"},
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
                    "default",
                    {"PATH": "test-path"},
                    [],
                ),
                process,
            )
        command = popen.call_args.args[0]
        self.assertIn("--spring.profiles.active=default", command)
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

    def test_platform_main_starts_only_server_and_always_cleans_up(self):
        for profile in ("default", "consumer"):
            server = MagicMock()
            with patch.object(launcher, "build_frontend"), patch.object(
                    launcher, "build_runtime_processes", return_value=Path("server.jar")), patch.object(
                    launcher, "start_server", return_value=server), patch.object(
                    launcher, "wait_for_server"), patch.object(
                    launcher, "supervise", return_value=0) as supervise, patch.object(
                    launcher, "stop_process") as stop:
                self.assertEqual(launcher.main(["--profile", profile], environ={}), 0)
                supervise.assert_called_once_with(server)
                stop.assert_called_once_with(server, "XA Mass Server")
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            launcher.parse_arguments(["--profile", "scenario-workers"])


if __name__ == "__main__":
    unittest.main()
