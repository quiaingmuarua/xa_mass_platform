import unittest
import json
import shutil
from unittest.mock import Mock, call, patch
import socket
import tempfile
import subprocess
import sys
import io
from contextlib import redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
import run_preview
from run_preview import Preview, all_pages, build
sys.path.insert(0, str(Path(run_preview.__file__).resolve().parents[2]))
import run_local_runtime


class PreviewLifecycleTest(unittest.TestCase):
    def test_both_entrypoints_reject_invalid_parameters_before_build_or_processes(self):
        for arguments in (["--count", "0"], ["--seed", "9223372036854775808"],
                          ["--app-count", "-1"], ["--app-count", "15001"],
                          ["--port", "0"], ["--port", "65532"], ["--count", "bad"],
                          ["--sandbox-root", "data/unrelated"]):
            for root_entry in (False, True):
                with self.subTest(arguments=arguments, root=root_entry), redirect_stderr(io.StringIO()), \
                     patch.object(run_preview, "build") as build_mock, \
                     patch.object(run_preview, "Preview") as process_mock, \
                     patch.object(run_local_runtime, "load_preview", return_value=run_preview):
                    with self.assertRaises(SystemExit) as error:
                        if root_entry:
                            run_local_runtime.main(["--profile", "preview", *arguments])
                        else:
                            run_preview.main(["--build", *arguments])
                    self.assertEqual(2, error.exception.code)
                    build_mock.assert_not_called()
                    process_mock.assert_not_called()

    def test_direct_preview_skips_build_by_default_and_root_builds_once(self):
        for root_entry in (False, True):
            with self.subTest(root=root_entry), patch.object(run_preview, "build") as build_mock, \
                 patch.object(run_preview, "Preview") as factory, patch.object(run_preview.signal, "signal"), \
                 patch.object(run_local_runtime, "load_preview", return_value=run_preview):
                factory.return_value.__enter__.return_value.check.side_effect = KeyboardInterrupt
                if root_entry:
                    self.assertEqual(0, run_local_runtime.main(["--profile", "preview"]))
                    build_mock.assert_called_once_with()
                else:
                    self.assertEqual(0, run_preview.main([]))
                    build_mock.assert_not_called()
                factory.assert_called_once_with(60, 18500, sandbox_root=None, seed=0, app_count=20)
                factory.return_value.__exit__.assert_called_once()

    def test_partial_start_failure_stops_owned_process_before_scope_cleanup(self):
        run = Preview()
        events = []
        process = Mock()
        process.poll.return_value = None
        process.terminate.side_effect = lambda: events.append("terminate")
        process.wait.side_effect = lambda **_: events.append("wait")
        client = run.redis = Mock()
        client.scan_iter.side_effect = lambda **_: (events.append("scan") or [])
        def partial_start():
            run.processes["server"] = process
            raise RuntimeError("Host startup failed")
        with patch.object(run, "start", side_effect=partial_start):
            with self.assertRaisesRegex(RuntimeError, "Host startup failed"):
                with run:
                    self.fail("Failed startup must not enter the body")
        self.assertEqual(["terminate", "wait", "scan"], events)
        self.assertTrue(run.closed)
        client.close.assert_called_once()

    def test_extracted_preview_never_builds_or_uses_stale_source_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "preview-manifest.json").write_text("{}")
            (root / "build/preview/server").mkdir(parents=True)
            (root / "build/preview/server/xa-mass-server-jvm-stale.jar").touch()
            run = Preview(root=root)
            client = Mock()
            client.info.return_value = {"redis_version": "7.4.10"}
            client.scan_iter.return_value = []
            with patch.object(run_preview, "PRODUCT", root), patch.object(run_preview.subprocess, "run") as execute:
                with self.assertRaisesRegex(RuntimeError, "does not support --build"):
                    build()
                execute.assert_not_called()
            with patch("run_preview.redis.Redis.from_url", return_value=client), \
                 patch("run_preview.socket.socket"), patch("run_preview.shutil.which", return_value="java"), \
                 patch("run_preview.subprocess.run", return_value=Mock(stdout='', stderr='openjdk version "21.0.1"')), \
                 patch.object(run, "launch") as launch:
                with self.assertRaisesRegex(RuntimeError, "Preview artifacts missing"):
                    with run:
                        self.fail("Missing packaged JAR must reject startup")
                launch.assert_not_called()

    def test_population_defaults_and_validation_do_not_accept_country_quota_arguments(self):
        self.assertEqual((60, 0), (Preview().count, Preview().seed))
        for count in (1, 10000):
            for seed in (0, 712, -1, -(2**63), 2**63 - 1):
                run = Preview(count=count, seed=seed)
                self.assertEqual((count, seed), (run.count, run.seed))
        for count in (0, 10001, True, 1.5, "60", (20, 20, 20)):
            with self.assertRaises(ValueError):
                Preview(count=count)
        for seed in (None, True, "712", 1.5, -(2**63)-1, 2**63):
            with self.assertRaises(ValueError):
                Preview(seed=seed)
        for arguments in (["--products", "sms"], ["--counts", "4,4,4"], ["--count", "0"], ["--seed", "9223372036854775808"]):
            result = subprocess.run([sys.executable, str(Path(run_preview.__file__)), *arguments],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(2, result.returncode, result.stderr)

    def test_idle_connection_is_retired_before_the_next_mutation(self):
        recent, fresh = Mock(), Mock()
        for connection in (recent, fresh):
            response = connection.getresponse.return_value
            response.status = 200
            response.read.return_value = b'{}'
        with patch.object(run_preview._connections, "items", {}, create=True), \
             patch("run_preview.http_client.HTTPConnection", side_effect=[recent, fresh]) as connect, \
             patch("run_preview.time.monotonic", side_effect=[0, 1, 1, 7, 7]):
            run_preview.http("http://127.0.0.1:18504", "/lab/health")
            run_preview.http("http://127.0.0.1:18504", "/lab/health")
            recent.close.assert_not_called()
            run_preview.http("http://127.0.0.1:18504", "/lab/action", {})
        self.assertEqual(2, connect.call_count)
        self.assertEqual(2, recent.request.call_count)
        recent.close.assert_called_once()
        fresh.request.assert_called_once_with("POST", "/lab/action", body=b'{}',
                                              headers={"Content-Type": "application/json"})

    def test_uncertain_mutation_is_never_replayed(self):
        connection = Mock()
        connection.getresponse.side_effect = ConnectionResetError("response lost")
        with patch.object(run_preview._connections, "items", {}, create=True), \
             patch("run_preview.http_client.HTTPConnection", return_value=connection) as connect:
            with self.assertRaises(ConnectionResetError):
                run_preview.http("http://127.0.0.1:18504", "/lab/action", {})
            self.assertEqual({}, run_preview._connections.items)
        connect.assert_called_once()
        connection.request.assert_called_once()
        connection.close.assert_called_once()

    def test_build_uses_the_distribution_executable_and_independent_host(self):
        with patch("run_preview.subprocess.run") as run:
            build()
        command = run.call_args.args[0]
        self.assertIn(":distribution:server:stagePreviewServer", command)
        self.assertIn(":distribution:server:stagePreviewFrontend", command)
        self.assertIn(":distribution:server:stagePreviewHost", command)

    def test_source_and_zip_compositions_use_two_jvms_and_one_server_address(self):
        for packaged, app_count in ((False, 20), (True, 20), (False, 0), (True, 0)):
            with self.subTest(packaged=packaged, app_count=app_count), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                if not packaged:
                    (root / "build.gradle").touch()
                server_lib = root / ("lib" if packaged else "build/preview/server")
                host_lib = root / ("worker-simulator/lib" if packaged else "build/preview/host/lib")
                frontend = root / ("frontend/dist" if packaged else "build/preview/frontend/dist")
                for folder in (server_lib, host_lib, frontend):
                    folder.mkdir(parents=True)
                shutil.copytree(Path(run_preview.__file__).resolve().parents[2] / "worker_simulator_jvm/config",
                                host_lib.parent / "config")
                (server_lib / "xa-mass-server-jvm-test.jar").write_bytes(b"fixture")
                (host_lib / "host.jar").write_bytes(b"fixture")
                run = Preview(root=root, port=18410, seed=712, app_count=app_count)
                client = Mock()
                client.info.return_value = {"redis_version": "7.4.10"}
                client.scan_iter.return_value = []
                version = Mock(stdout="", stderr='openjdk version "21.0.1"')
                with patch("run_preview.redis.Redis.from_url", return_value=client), \
                     patch("run_preview.socket.socket"), patch("run_preview.shutil.which", return_value="java"), \
                     patch("run_preview.subprocess.run", return_value=version), \
                     patch.object(run, "launch") as launch, patch.object(run, "wait_for"):
                    run.start()
                    self.assertEqual(["server", "host"], [item.args[0] for item in launch.call_args_list])
                    server, host = launch.call_args_list
                    self.assertIn("--spring.profiles.active=preview", server.args[1])
                    self.assertIn("--spring.config.additional-location=" + (root / ("config" if packaged else "build/preview/config")).as_uri() + "/", server.args[1])
                    self.assertIn(str(server_lib / "xa-mass-server-jvm-test.jar"), server.args[1])
                    self.assertIn("--server.tomcat.accesslog.pattern=%m %U %s", server.args[1])
                    self.assertIn("--server.tomcat.accesslog.buffered=false", server.args[1])
                    self.assertIn(str(host_lib / "*"), host.args[1])
                    self.assertEqual(["--config", str(run.worker_config_path)], host.args[1][-2:])
                    config = json.loads(run.worker_config_path.read_text(encoding="utf-8"))
                    self.assertEqual("http://127.0.0.1:18410", config["runtimeApiBaseUrl"])
                    self.assertEqual(18414, config["controlPort"])
                    self.assertEqual(712, config["seed"])
                    self.assertEqual(str(root / "data/scenario-workers"), config["sandboxRoot"])
                    group = config["workerGroups"]["demo-sim"]
                    self.assertEqual(60, group["count"])
                    self.assertFalse(group["newEnvironment"])
                    self.assertEqual({"$choice": ["CN", "US", "GB"]}, group["propertiesTemplate"]["country"])
                    self.assertEqual(True, "extension.worker.sms.listen.start" in group["events"])
                    self.assertEqual(True, "extension.worker.message.send" in group["events"])
                    self.assertEqual("18413", host.args[2]["PREVIEW_ADAPTER_PORT"])
                    for group_id in ("app-a-sim", "app-b-sim"):
                        if app_count:
                            self.assertEqual(app_count, config["workerGroups"][group_id]["count"])
                            self.assertEqual(["extension.worker.app.registration.check"], config["workerGroups"][group_id]["events"])
                        else:
                            self.assertNotIn(group_id, config["workerGroups"])
                    record = json.loads((run.output / "run.json").read_text())
                    self.assertEqual((60, 712), (record["count"], record["seed"]))
                    self.assertNotIn("counts", record)
                    self.assertEqual({"server", "hostClasspath", "frontendSha256"}, run.artifacts.keys())
                    run.close()

    def test_readiness_uses_discovered_inventory_not_initialization_counts(self):
        run = Preview(count=1000, sandbox_root=Path("test/data/scenario-workers"))
        with patch("run_preview.http", return_value={"workers": [
            {"workerGroupId": group, "workerId": group + "-id", "runtimeState": "RUNNING"}
            for group in ("demo-sim", "app-a-sim", "app-b-sim")
        ]}) as get:
            self.assertTrue(run.host_ready())
            get.assert_called_once_with(run.host, "/lab/v1/workers")
        with patch("run_preview.http", return_value={"workers": []}):
            self.assertTrue(run.host_ready())
        with patch("run_preview.http", return_value={"workers": [{"workerGroupId": "app-a-sim", "workerId": None, "runtimeState": "STOPPED"}]}):
            self.assertFalse(run.host_ready())

    def test_app_count_has_its_own_host_group_bound(self):
        self.assertEqual(0, Preview(app_count=0).app_count)
        self.assertEqual(2, Preview(app_count=2).app_count)
        for value in (-1, 15001, True, 1.5):
            with self.assertRaisesRegex(ValueError, "app-count"):
                Preview(app_count=value)

    def test_inventory_reads_bounded_pages_and_checks_progress(self):
        with patch("run_preview.http", side_effect=[
            {"total": 3, "items": ["a", "b"]}, {"total": 3, "items": ["c"]}
        ]) as request:
            self.assertEqual(["a", "b", "c"], all_pages("host", "/lab/v1/sms/inventory"))
            self.assertEqual([call("host", "/lab/v1/sms/inventory?offset=0&limit=1000"),
                              call("host", "/lab/v1/sms/inventory?offset=2&limit=1000")], request.call_args_list)
        with patch("run_preview.http", return_value={"total": 1, "items": []}):
            with self.assertRaisesRegex(RuntimeError, "Pagination"):
                all_pages("host", "/lab/v1/sms/inventory")

    def test_port_conflict_starts_no_jvm_and_does_not_close_occupied_socket(self):
        with socket.socket() as occupied, tempfile.TemporaryDirectory() as output:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            run = Preview(port=occupied.getsockname()[1], output=output)
            client = Mock()
            client.info.return_value = {"redis_version": "7.4.0"}
            client.scan_iter.return_value = []
            with patch("run_preview.redis.Redis.from_url", return_value=client), \
                 patch("run_preview.subprocess.Popen") as launch:
                with self.assertRaises(OSError):
                    with run:
                        self.fail("Occupied port must reject startup")
                launch.assert_not_called()
                self.assertNotEqual(-1, occupied.fileno())
                self.assertTrue(run.closed)

    def test_cleanup_is_exact_scope_and_uses_unlink(self):
        run = Preview()
        client = run.redis = Mock()
        own = f"xa_mass:{run.scope}:worker:one".encode()
        client.scan_iter.return_value = [own]
        run.close()
        client.scan_iter.assert_called_once_with(match=f"xa_mass:{run.scope}:*", count=1000)
        client.unlink.assert_called_once_with(own)
        run.close()
        client.unlink.assert_called_once()

    def test_unexpected_key_never_deleted(self):
        run = Preview()
        client = run.redis = Mock()
        client.scan_iter.return_value = [b"xa_mass:another_scope:worker:one"]
        with self.assertRaisesRegex(RuntimeError, "Unexpected"):
            run.close()
        client.unlink.assert_not_called()

    def test_live_child_failure_ends_run_instead_of_restarting(self):
        run = Preview()
        process = Mock(returncode=7)
        process.poll.return_value = 7
        run.processes["host"] = process
        with self.assertRaisesRegex(RuntimeError, "host exited"):
            run.check()
        process.terminate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
