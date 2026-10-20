import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_workers import AndroidApps


class LocalAndroidTest(unittest.TestCase):
    def owner(self, count=1):
        owner = AndroidApps(count, "device", "http://127.0.0.1:19482", 19483, Path("output"), {})
        owner.apk_root = Path("apks")
        owner.provenance = {"apkSha256": {name: "hash" for _, name, _ in owner.apps}}
        owner.adb_path = "adb"
        return owner

    def fake_device(self, owner, fail_start=False):
        mappings = {"reverse": {"tcp:19482": "tcp:old"}, "forward": {}}
        calls = []
        def adb(*args, **kwargs):
            calls.append(args)
            if args[0] in mappings:
                table = mappings[args[0]]
                if args[1] == "--list":
                    return "\n".join(f"device {key} {value}" for key, value in table.items())
                if args[1] == "--remove":
                    table.pop(args[2], None)
                else:
                    table[args[1]] = args[2]
            if args[:3] == ("shell", "am", "start") and fail_start:
                raise RuntimeError("response lost after App launch")
            return ""
        owner.adb = Mock(side_effect=adb)
        return mappings, calls

    def test_three_packages_share_device_and_use_resolved_ports(self):
        owner = self.owner(3)
        mappings, calls = self.fake_device(owner)
        with patch.object(owner, "installed_digest", return_value="hash"), patch.object(owner, "worker_ids", return_value=["a", "b", "c"]):
            owner.start()
        self.assertEqual(3, len(owner.started))
        self.assertEqual(3, len(set(owner.started)))
        self.assertFalse(any(args[0] == "install" for args in calls))
        self.assertEqual("tcp:19483", mappings["reverse"]["tcp:19483"])
        self.assertEqual([], owner.close())
        self.assertEqual({"tcp:19482": "tcp:old"}, mappings["reverse"])
        self.assertEqual({}, mappings["forward"])
        self.assertEqual(3, sum(args[:3] == ("shell", "am", "force-stop") for args in calls))
        self.assertFalse(any("clear" in args or "uninstall" in args or "settings" in args for args in calls))

    def test_ambiguous_start_is_owned_and_cleanup_restores_prior_mapping(self):
        owner = self.owner()
        mappings, calls = self.fake_device(owner, fail_start=True)
        with patch.object(owner, "installed_digest", return_value="hash"), self.assertRaisesRegex(RuntimeError, "response lost"):
            owner.start()
        self.assertEqual(1, len(owner.started))
        self.assertEqual([], owner.close())
        self.assertEqual({"tcp:19482": "tcp:old"}, mappings["reverse"])
        self.assertEqual({}, mappings["forward"])

    def test_install_failure_does_not_uninstall_or_touch_mappings(self):
        owner = self.owner()
        owner.adb = Mock(side_effect=lambda *args, **kwargs: "" if args[0] == "shell" else (_ for _ in ()).throw(RuntimeError("signature")))
        with patch.object(owner, "installed_digest", return_value="different"), self.assertRaisesRegex(RuntimeError, "signature"):
            owner.start()
        self.assertEqual([], owner.started)
        self.assertEqual([], owner.mappings)
        self.assertEqual([], owner.close())

    def test_external_app_start_during_build_is_not_taken_over(self):
        owner = self.owner()
        owner.adb = Mock(return_value="1234")
        with self.assertRaisesRegex(RuntimeError, "became active"):
            owner.start()
        self.assertEqual([], owner.close())
        self.assertFalse(any(call.args[:3] == ("shell", "am", "force-stop") for call in owner.adb.call_args_list))

    def test_external_mapping_change_is_reported_and_preserved(self):
        owner = self.owner()
        mappings, _ = self.fake_device(owner)
        owner.map_port("reverse", 19482)
        mappings["reverse"]["tcp:19482"] = "tcp:external"
        self.assertIn("changed externally", owner.close()[0])
        self.assertEqual("tcp:external", mappings["reverse"]["tcp:19482"])

    def test_device_selection_is_explicit_when_ambiguous(self):
        owner = self.owner()
        owner.serial = None
        with patch("local_workers.shutil.which", return_value="adb"), patch("local_workers.subprocess.run",
                return_value=Mock(stdout="List of devices attached\nfirst\tdevice\nsecond\tdevice\n")):
            with self.assertRaisesRegex(ValueError, "Select --android-device"):
                owner.preflight()


if __name__ == "__main__":
    unittest.main()
