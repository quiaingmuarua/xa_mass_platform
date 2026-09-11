import unittest
import json
import shutil
from unittest.mock import Mock, call, patch
import socket
import tempfile
import subprocess
import sys
from pathlib import Path

import run_preview
from run_preview import Preview, all_pages, build


class PreviewLifecycleTest(unittest.TestCase):
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
        for arguments in (["--counts", "4,4,4"], ["--count", "0"], ["--seed", "9223372036854775808"]):
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
        self.assertIn(":distribution:product-preview:stageServer", command)
        self.assertIn(":distribution:product-preview:stageFrontend", command)
        self.assertIn(":distribution:product-preview:stageHost", command)

    def test_source_and_zip_compositions_use_two_jvms_and_one_server_address(self):
        combinations = [
            ("sms", "sms-reception"),
            ("messages", "message-campaigns"),
            ("sms,messages", "sms-reception,message-campaigns"),
        ]
        for packaged in (False, True):
            for products, profiles in combinations:
                with self.subTest(packaged=packaged, products=products), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    server_lib = root / ("lib" if packaged else "build/server")
                    host_lib = root / ("worker-simulator/lib" if packaged else "build/host/lib")
                    frontend = root / ("frontend/dist" if packaged else "build/frontend/dist")
                    for folder in (server_lib, host_lib, frontend):
                        folder.mkdir(parents=True)
                    shutil.copytree(Path(__file__).resolve().parents[2] / "worker_simulator_jvm/config",
                                    host_lib.parent / "config")
                    (server_lib / "xa-mass-server-jvm-test.jar").write_bytes(b"fixture")
                    (host_lib / "host.jar").write_bytes(b"fixture")
                    run = Preview(root=root, port=18410, products=products, seed=712)
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
                        self.assertIn("--spring.profiles.active=product-preview," + profiles, server.args[1])
                        self.assertIn("--spring.config.additional-location=" + (root / "config").as_uri() + "/", server.args[1])
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
                        self.assertEqual("sms" in products, "extension.worker.sms.listen.start" in group["events"])
                        self.assertEqual("messages" in products, "extension.worker.message.send" in group["events"])
                        self.assertEqual("18413", host.args[2]["PREVIEW_ADAPTER_PORT"])
                        record = json.loads((run.output / "run.json").read_text())
                        self.assertEqual((60, 712), (record["count"], record["seed"]))
                        self.assertNotIn("counts", record)
                        self.assertEqual({"server", "hostClasspath", "frontendSha256"}, run.artifacts.keys())
                        run.close()

    def test_readiness_uses_discovered_inventory_not_initialization_counts(self):
        run = Preview(count=1000, sandbox_root=Path("test/data/scenario-workers"))
        with patch("run_preview.http", return_value={"started": True, "prepared": 3, "numbers": 3}):
            self.assertTrue(run.host_ready())
        with patch("run_preview.http", return_value={"started": True, "prepared": 0, "numbers": 0}):
            self.assertTrue(run.host_ready())
        with patch("run_preview.http", return_value={"started": True, "prepared": 2, "numbers": 3}):
            self.assertFalse(run.host_ready())

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
