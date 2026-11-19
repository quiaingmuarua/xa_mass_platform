import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import re
import unittest
from unittest.mock import patch
import urllib.request
import zipfile

SCRIPT = Path(__file__).resolve().parents[2] / ".github/scripts/prepare_external_release_load.py"
SPEC = importlib.util.spec_from_file_location("prepare_external_release_load", SCRIPT)
DOWNLOAD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOWNLOAD)


class ExternalReleaseDownloadTest(unittest.TestCase):
    def setUp(self):
        self.repository = "owner/repo"
        self.tag, self.commit = "v5.0.0", "a" * 40
        self.name = "xa-mass-worker-simulator-5.0.0.zip"
        self.manifest = {"schemaVersion": 3, "version": "5.0.0", "gitCommit": self.commit, "javaVersion": 21,
                         "simulatorEntry": "bin/xa-mass-worker-simulator", "roundEntry": "acceptance/finite_round.py",
                         "loadedEntry": "acceptance/run_loaded.py",
                         "performanceEntry": "acceptance/performance/run_worker_call_performance.py"}
        self.release = {"tag_name": self.tag, "draft": False, "immutable": True,
                        "id": 123, "html_url": "https://github.com/owner/repo/releases/tag/v5.0.0",
                        "assets": [{"name": self.name, "state": "uploaded"}, {"name": self.name + ".sha256", "state": "uploaded"}]}

    def api(self, endpoint):
        if "/releases/tags/" in endpoint: return self.release
        return {"object": {"type": "commit", "sha": self.commit}}

    def download(self, asset, destination, url):
        if destination.suffix == ".zip":
            with zipfile.ZipFile(destination, "w") as archive:
                archive.writestr("xa-mass-worker-simulator-5.0.0/manifest.json", json.dumps(self.manifest))
                archive.writestr("xa-mass-worker-simulator-5.0.0/acceptance/run_loaded.py", "# published entry\n")
                archive.writestr("xa-mass-worker-simulator-5.0.0/acceptance/performance/run_worker_call_performance.py", "# existing runner\n")
        else:
            digest = hashlib.sha256(destination.with_suffix("").read_bytes()).hexdigest()
            destination.write_text(digest + "  " + self.name + "\n")

    def test_only_verified_immutable_matching_release_can_be_consumed(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(DOWNLOAD, "api", side_effect=self.api), \
             patch.object(DOWNLOAD, "download", side_effect=self.download), patch.object(DOWNLOAD, "verify_simulator") as verify:
            output = Path(directory) / "installation"
            result = DOWNLOAD.prepare(self.repository, self.tag, self.commit, output)
            verify.assert_called_once_with(output / self.name, "5.0.0")
            self.assertEqual(self.commit, result["gitCommit"])
            self.assertEqual(self.manifest, result["manifest"])
            self.assertTrue((Path(result["kitRoot"]) / "acceptance/run_loaded.py").is_file())
            self.assertTrue((Path(result["kitRoot"]) / self.manifest["performanceEntry"]).is_file())
            self.assertEqual(result, json.loads((output / "consumption.json").read_text()))

    def test_draft_mutable_and_wrong_tag_are_rejected_before_download(self):
        for change in ({"draft": True}, {"immutable": False}, {"tag_name": "v4.0.0"}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory, \
                 patch.object(DOWNLOAD, "api", return_value={**self.release, **change}), patch.object(DOWNLOAD, "download") as download:
                with self.assertRaises(ValueError):
                    DOWNLOAD.prepare(self.repository, self.tag, self.commit, Path(directory) / "installation")
                download.assert_not_called()

    def test_installed_commit_and_tag_must_agree(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(DOWNLOAD, "api", side_effect=[
            self.release, {"object": {"type": "commit", "sha": "b" * 40}}]), patch.object(DOWNLOAD, "download") as download:
            with self.assertRaisesRegex(ValueError, "installed commit"):
                DOWNLOAD.prepare(self.repository, self.tag, self.commit, Path(directory) / "installation")
            download.assert_not_called()

    def test_old_kit_and_wrong_manifest_identity_cannot_supply_the_kit(self):
        for change in ({"schemaVersion": 1}, {"schemaVersion": 2}, {"gitCommit": "b" * 40},
                       {"loadedEntry": "another.py"}, {"performanceEntry": "another.py"}):
            original = dict(self.manifest)
            self.manifest.update(change)
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory, \
                 patch.object(DOWNLOAD, "api", side_effect=self.api), patch.object(DOWNLOAD, "download", side_effect=self.download), \
                 patch.object(DOWNLOAD, "verify_simulator"):
                with self.assertRaisesRegex(ValueError, "capacity entry"):
                    DOWNLOAD.prepare(self.repository, self.tag, self.commit, Path(directory) / "installation")
            self.manifest = original

    def test_sidecar_mismatch_is_not_hidden_by_release_metadata(self):
        def changed_download(asset, destination, url):
            self.download(asset, destination, url)
            if destination.suffix == ".sha256": destination.write_text("0" * 64 + "  " + self.name)
        with tempfile.TemporaryDirectory() as directory, patch.object(DOWNLOAD, "api", side_effect=self.api), \
             patch.object(DOWNLOAD, "download", side_effect=changed_download), patch.object(DOWNLOAD, "verify_simulator") as verify:
            with self.assertRaisesRegex(ValueError, "sidecar"):
                DOWNLOAD.prepare(self.repository, self.tag, self.commit, Path(directory) / "installation")
            verify.assert_not_called()

    def test_existing_installation_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(DOWNLOAD, "api") as api:
            with self.assertRaisesRegex(ValueError, "already exists"):
                DOWNLOAD.prepare(self.repository, self.tag, self.commit, Path(directory))
            api.assert_not_called()

    def test_download_checks_https_fixed_coordinate_size_and_digest(self):
        payload = b"verified release asset"
        url = "https://github.com/owner/repo/releases/download/v5.0.0/test.zip"
        asset = {"browser_download_url": url, "size": len(payload), "digest": "sha256:" + hashlib.sha256(payload).hexdigest()}
        for content, succeeds in ((payload, True), (payload + b"extra", False), (b"changed", False)):
            with self.subTest(succeeds=succeeds), tempfile.TemporaryDirectory() as directory, \
                 patch.object(urllib.request, "build_opener") as opener:
                opener.return_value.open.return_value = io.BytesIO(content)
                destination = Path(directory) / "asset.zip"
                if succeeds:
                    DOWNLOAD.download(asset, destination, url)
                    self.assertEqual(payload, destination.read_bytes())
                else:
                    with self.assertRaises(ValueError): DOWNLOAD.download(asset, destination, url)
                    self.assertFalse(destination.exists())
                request = opener.return_value.open.call_args.args[0]
                self.assertFalse(any(key.lower() == "authorization" for key in request.headers))

    def test_download_does_not_follow_plaintext_redirect(self):
        request = urllib.request.Request("https://github.com/owner/repo/asset")
        with self.assertRaisesRegex(ValueError, "insecure"):
            DOWNLOAD.HttpsRedirects().redirect_request(request, None, 302, "Found", {}, "http://untrusted.test/asset")


class LocalRecoveryWorkflowTest(unittest.TestCase):
    def test_scheduled_and_manual_runs_keep_the_local_recovery_world(self):
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/worker-loaded-recovery.yml").read_text(encoding="utf-8")
        header, jobs = text.split("\njobs:\n", 1)
        self.assertIn('cron: "0 18 * * *"', header)
        self.assertIn("  workflow_dispatch:\n", header)
        self.assertIn("  group: worker-loaded-recovery\n", header)
        self.assertIn("  cancel-in-progress: false", header)
        self.assertEqual(["worker-loaded-recovery"], re.findall(r"^  ([a-z][a-z0-9-]+):\s*$", jobs, re.MULTILINE))
        admission = jobs.split("    steps:\n", 1)[0]
        self.assertNotIn("    if:", admission)
        self.assertIn("runs-on: ubuntu-24.04", admission)
        self.assertIn("services:", admission)
        self.assertIn("image: redis:7.4.10", admission)
        for argument in ("--prepared-workers 15000", "--retained-workers 10000",
                         "--minimum-initial-connected 14800", "--minimum-retained-connected 9900",
                         "--workload-items-per-task 5000", "--redis-url redis://127.0.0.1:6379/15"):
            self.assertIn(argument, jobs)
        # This workflow proves the disposable source world. Public deployment
        # coordinates and credentials belong exclusively to Performance's entry.
        for boundary in ("environment:", "secrets.", "vars.", "inputs."):
            self.assertNotIn(boundary, text)


if __name__ == "__main__":
    unittest.main()
