import unittest
from unittest.mock import Mock, call, patch
import socket
import tempfile
from pathlib import Path

from run_preview import Preview, all_pages, build


class PreviewLifecycleTest(unittest.TestCase):
    def test_build_uses_the_distribution_executable_and_independent_host(self):
        with patch("run_preview.subprocess.run") as run:
            build()
        command = run.call_args.args[0]
        self.assertIn(":distribution:server:bootJar", command)
        self.assertIn(":scenario_workers_jvm:installDist", command)
        self.assertNotIn(":server_jvm:bootJar", command)
        self.assertNotIn(":products:sms-reception:backend:bootJar", command)

    def test_packaged_start_uses_two_jvms_and_one_server_address(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "lib").mkdir()
            (root / "lib/xa-mass-server-jvm-test.jar").write_bytes(b"fixture")
            (root / "scenario-workers/lib").mkdir(parents=True)
            (root / "scenario-workers/lib/host.jar").write_bytes(b"fixture")
            (root / "frontend/dist").mkdir(parents=True)
            run = Preview(root=root, port=18410)
            client = Mock()
            client.info.return_value = {"redis_version": "7.4.10"}
            client.scan_iter.return_value = []
            version = Mock(stdout="", stderr='openjdk version "21.0.1"')
            with patch("run_preview.redis.Redis.from_url", return_value=client), \
                 patch("run_preview.socket.socket"), patch("run_preview.shutil.which", return_value="java"), \
                 patch("run_preview.subprocess.run", return_value=version), \
                 patch.object(run, "launch") as launch, patch.object(run, "wait_for"):
                run.start()
                self.assertEqual(["server", "host"], [call.args[0] for call in launch.call_args_list])
                server, host = launch.call_args_list
                self.assertIn("--spring.profiles.active=sms-reception", server.args[1])
                self.assertIn("--runtime-api-base-url=http://127.0.0.1:18410", host.args[1])
                self.assertIn("--scenario=sms", host.args[1])
                self.assertEqual("18413", host.args[2]["SMS_ADAPTER_PORT"])
                self.assertIn("--control-port=18414", host.args[1])
                self.assertIn("--sms-counts=20,20,20", host.args[1])
                self.assertNotIn("SMS_PLATFORM_PORT", host.args[2])
                self.assertEqual({"server", "hostClasspath"}, run.artifacts.keys())
                run.close()

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
