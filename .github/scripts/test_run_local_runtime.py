from __future__ import annotations
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "distribution/server"))
import run_local_runtime as launcher
import local_runtime as local


class LocalRuntimeLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="local 中文 ")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def document(self, name, value):
        path = self.directory / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return str(path)

    def resolve(self, *arguments):
        return local.resolve(local.parse_arguments(list(arguments)))

    def test_profiles_do_not_infer_business_from_worker_type(self):
        for profile, port, adapter in (("default", 18082, None), ("consumer", 18182, 18183)):
            settings = self.resolve("--profile", profile)
            self.assertEqual((port, adapter), (settings["port"], settings["adapter_port"]))
            self.assertIsNone(settings["java_config"])
            self.assertIsNone(settings["groups"])
            self.assertFalse(settings["temporary_scope"])
        preview = self.resolve()
        self.assertEqual({"demo-sim": 60, "app-a-sim": 20, "app-b-sim": 20}, preview["java_config"]["startupPlan"]["workerCounts"])
        self.assertTrue(preview["temporary_scope"])
        android = self.resolve("--profile", "consumer", "--android-count", "3")
        self.assertIsNone(android["runtime_config"])
        self.assertIsNone(android["groups"])

    def test_cli_file_defaults_precedence_and_group_replacement(self):
        runtime = self.document("runtime.yaml", {"server": {"port": 19382}, "xa": {"mass": {
            "redis": {"scope": "my_scope"}, "worker-assembly": {"group-config-json": "{}"},
            "worker-delivery": {"adapter": {"instances": {"runtime-websocket": {"listen-port": 19383}}}}}}})
        groups = self.document("groups.json", {"custom": {"attributes": {}, "eventCodes": ["extension.worker.custom"]}})
        settings = self.resolve("--profile", "consumer", "--runtime-config", runtime, "--groups", groups)
        self.assertEqual((19382, 19383, "my_scope"), (settings["port"], settings["adapter_port"], settings["scope"]))
        overridden = self.resolve("--profile", "consumer", "--runtime-config", runtime, "--server-port", "19482", "--adapter-port", "19483")
        self.assertEqual((19482, 19483), (overridden["port"], overridden["adapter_port"]))
        run = local.LocalRun(settings, output=self.directory / "run")
        run.server_jar, run.frontend = self.directory / "server.jar", self.directory / "frontend"
        command = run.server_arguments()
        self.assertIn("--server.port=19382", command)
        self.assertIn("--xa.mass.worker-delivery.adapter.remote-base-url=http://127.0.0.1:19382", command)
        self.assertIn("--xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri=ws://127.0.0.1:19383/api/v1/worker-delivery/websocket", command)
        self.assertEqual(settings["groups"], json.loads(next(x.split("=", 1)[1] for x in command if "group-config-json=" in x)))
        self.assertFalse(any("project-id" in x for x in command))

    def test_preview_adapter_callbacks_follow_the_selected_http_port(self):
        settings = self.resolve("--server-port", "19400", "--adapter-port", "19403")
        run = local.LocalRun(settings, output=self.directory / "run")
        run.server_jar, run.frontend = self.directory / "server.jar", self.directory / "frontend"
        self.assertIn("--xa.mass.worker-delivery.adapter.remote-base-url=http://127.0.0.1:19400",
                      run.server_arguments())

    def test_environment_cannot_override_runtime_or_frontend(self):
        values = {"PATH": "tools", "JAVA_HOME": "jdk", "ANDROID_HOME": "sdk", "SERVER_PORT": "1",
                  "XA_MASS_REDIS_SCOPE": "other", "SPRING_APPLICATION_JSON": "{}", "JAVA_TOOL_OPTIONS": "-Dserver.port=2",
                  "VITE_RUNTIME_DATA_SOURCE": "mock", "PREVIEW_SERVER_PORT": "3"}
        self.assertEqual({"PATH": "tools", "JAVA_HOME": "jdk", "ANDROID_HOME": "sdk"}, local.child_environment(values))
        with patch.dict(os.environ, values):
            self.assertEqual(18182, self.resolve("--profile", "consumer")["port"])

    def test_literal_dotted_yaml_coordinates_are_not_silently_overridden(self):
        path = self.document("dotted.yaml", {"server.port": 19382, "xa.mass.redis.scope": "explicit"})
        settings = self.resolve("--profile", "consumer", "--runtime-config", path)
        self.assertEqual((19382, "explicit"), (settings["port"], settings["scope"]))
        path = self.document("conditional.yaml", {"spring.config.activate.on-profile": "other"})
        with self.assertRaisesRegex(ValueError, "profile"):
            self.resolve("--profile", "consumer", "--runtime-config", path)

    def test_java_counts_and_all_zero_never_reopen_inventory(self):
        args = ["--java-count", "demo-sim=2", "--java-count", "app-a-sim=0", "--java-count", "app-b-sim=0"]
        settings = self.resolve(*args, "--work-dir", str(self.directory), "--server-port", "19400")
        self.assertEqual({"demo-sim": 2, "app-a-sim": 0, "app-b-sim": 0}, settings["java_config"]["startupPlan"]["workerCounts"])
        self.assertEqual("http://127.0.0.1:19400", settings["java_config"]["runtimeApiBaseUrl"])
        self.assertEqual(str((self.directory / "data/scenario-workers").resolve()), settings["java_config"]["sandboxRoot"])
        args[1] = "demo-sim=0"
        self.assertIsNone(self.resolve(*args)["java_config"])
        self.assertIsNone(self.resolve("--no-java-workers")["java_config"])

    def test_java_file_relative_paths_and_mixed_workers(self):
        config = self.document("java.json", {"workerGroups": {"custom": {"count": 4}}, "sandboxRoot": "data/scenario-workers"})
        settings = self.resolve("--profile", "consumer", "--java-config", config, "--android-count", "2")
        self.assertEqual(2, settings["android_count"])
        self.assertEqual(4, settings["java_config"]["startupPlan"]["workerCounts"]["custom"])
        self.assertEqual(str((self.directory / "data/scenario-workers").resolve()), settings["java_config"]["sandboxRoot"])
        self.assertEqual("http://127.0.0.1:18182", settings["java_config"]["runtimeApiBaseUrl"])

    def test_invalid_inputs_fail_before_build(self):
        for args in (["--server-port", "0"], ["--server-port", "18503"], ["--java-count", "unknown=2"],
                     ["--java-count", "demo-sim=-1"], ["--runtime-config", str(self.directory / "missing")]):
            with self.subTest(args=args), patch.object(launcher, "build") as build, redirect_stderr(io.StringIO()):
                self.assertEqual(1, launcher.main(args))
                build.assert_not_called()
        bad = self.document("bad.yaml", {"server": {"port": "${SERVER_PORT}"}})
        with self.assertRaises(ValueError):
            self.resolve("--runtime-config", bad)
        duplicate = self.directory / "duplicate.yaml"
        duplicate.write_text("server: {}\nserver: {}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.resolve("--runtime-config", str(duplicate))

    def test_retired_options_are_rejected_without_aliases(self):
        for flag in ("--example", "--count", "--app-count", "--port", "--sandbox-root", "--seed"):
            with self.subTest(flag=flag), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                local.parse_arguments([flag, "1"])

    def test_explicit_coordinates_cannot_be_silently_replaced(self):
        config = self.document("coordinates.json", {"workerGroups": {"g": {"count": 3}},
                    "startupPlan": {"initialWorkers": [], "scheduledStops": []}})
        with self.assertRaisesRegex(ValueError, "startupPlan"):
            self.resolve("--profile", "consumer", "--java-config", config, "--java-count", "g=2")

    def test_build_selection_has_no_business_branch(self):
        with patch.object(launcher.subprocess, "run") as build:
            launcher.build(self.resolve("--profile", "consumer"))
            self.assertNotIn(":distribution:server:stagePreviewHost", build.call_args.args[0])
        with patch.object(launcher.subprocess, "run") as build, patch.object(launcher, "source_signature", return_value="source"), \
                patch.object(launcher, "candidate_current", return_value=False), patch.object(launcher, "remember_candidate"):
            launcher.build(self.resolve("--profile", "consumer", "--android-count", "1"))
            self.assertIn(":distribution:worker-sdk:distZip", build.call_args.args[0])

    def test_unchanged_source_reuses_checked_zip_bytes_and_corruption_invalidates_cache(self):
        archives = {name: self.directory / (name + ".zip") for name in ("runtime", "sdk")}
        for path in archives.values():
            path.write_bytes(b"checked-fixture")
        with patch.object(launcher, "CANDIDATE_CACHE", self.directory / "candidate.json"), \
                patch.object(launcher, "candidate_archives", return_value=archives):
            launcher.remember_candidate("same-source", "1.2.3")
            self.assertTrue(launcher.candidate_current("same-source", "1.2.3"))
            self.assertFalse(launcher.candidate_current("changed-source", "1.2.3"))
            archives["sdk"].write_bytes(b"changed-sdk")
            self.assertFalse(launcher.candidate_current("same-source", "1.2.3"))

    def test_repeated_android_launch_does_not_republish_identical_candidates(self):
        with patch.object(launcher.subprocess, "run") as build, patch.object(launcher, "source_signature", return_value="source"), \
                patch.object(launcher, "candidate_current", return_value=True), patch.object(launcher, "remember_candidate") as remember:
            launcher.build(self.resolve("--profile", "consumer", "--android-count", "3"))
            self.assertNotIn(":distribution:worker-sdk:distZip", build.call_args.args[0])
            self.assertNotIn(":distribution:server:distZip", build.call_args.args[0])
            remember.assert_not_called()

    def test_main_always_closes_after_partial_start_or_keyboard_interrupt(self):
        for failure in (RuntimeError("partial"), KeyboardInterrupt()):
            run = Mock(url="http://127.0.0.1:18182", scope="profile_consumer", android=None, environment={})
            run.start.side_effect = failure
            with patch.object(launcher, "LocalRun", return_value=run), patch.object(launcher, "build"), redirect_stderr(io.StringIO()):
                result = launcher.main(["--profile", "consumer"])
            self.assertEqual(0 if isinstance(failure, KeyboardInterrupt) else 1, result)
            run.close.assert_called_once()

    def test_explicit_scope_is_never_deleted(self):
        run = local.LocalRun(self.resolve("--profile", "consumer"), output=self.directory)
        client = run.redis = Mock()
        run.close()
        client.scan_iter.assert_not_called()
        client.close.assert_called_once()

    def test_failed_process_cleanup_retains_runtime_data(self):
        run = local.LocalRun(self.resolve(), output=self.directory)
        client = run.redis = Mock()
        with patch.object(run, "close_processes", return_value=["server PID 12 still running"]):
            with self.assertRaisesRegex(RuntimeError, "PID 12"):
                run.close()
        client.scan_iter.assert_not_called()
        client.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
