from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_archive import verify


class PreviewArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.frontend = self.directory / "frontend"
        (self.frontend / "static/js").mkdir(parents=True)
        (self.frontend / "index.html").write_text("console", encoding="utf-8")
        (self.frontend / "static/js/SmsPage-one.js").write_text("sms", encoding="utf-8")
        (self.frontend / "static/js/MessagePage-one.js").write_text("messages", encoding="utf-8")
        (self.frontend / "reference").mkdir()
        (self.frontend / "reference/platform-diagnostic-codes.json").write_text(
            json.dumps({"gitCommit": "a" * 40}), encoding="utf-8")

    def archive(self, *, embedded=False, extra=None):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as bundle:
            bundle.writestr("BOOT-INF/classes/Application.class", b"server")
            bundle.writestr("BOOT-INF/lib/xa-mass-message-campaigns-test.jar", b"messages")
            if embedded:
                bundle.writestr("BOOT-INF/classes/sms-frontend/index.html", b"old")
        path = self.directory / "preview.zip"
        root = "xa-mass-product-preview-0.1.0-preview/"
        files = {name: b"launcher" for name in (
            "run_preview.py", "requirements.txt", "README.md", "config/application-product-preview.yaml")}
        files["worker-simulator/lib/xa-mass-worker-simulator-test.jar"] = b"host"
        for name in ("lab", "sms", "messages", "products"):
            files["worker-simulator/config/" + name + ".json"] = b"{}"
        files["lib/server.jar"] = jar.getvalue()
        for asset in self.frontend.rglob("*"):
            if asset.is_file():
                files["frontend/dist/" + asset.relative_to(self.frontend).as_posix()] = asset.read_bytes()
        files["preview-manifest.json"] = json.dumps({
            "defaultProducts": ["sms", "messages"], "processModel": ["server", "host"], "gitCommit": "a" * 40,
            "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
        }).encode()
        with zipfile.ZipFile(path, "w") as bundle:
            for name, data in files.items():
                bundle.writestr(root + name, data)
            if extra:
                bundle.writestr(extra, "unexpected")
        return path

    def test_accepts_current_unified_build(self):
        self.assertTrue(verify(self.archive(), self.frontend)["passed"])

    def test_requires_each_complete_simulator_example(self):
        original = self.archive()
        incomplete = self.directory / "incomplete.zip"
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(incomplete, "w") as target:
            for name in source.namelist():
                if not name.endswith("/worker-simulator/config/lab.json"):
                    target.writestr(name, source.read(name))
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            verify(incomplete, self.frontend)

    def test_never_packages_materialized_inventory(self):
        with self.assertRaisesRegex(ValueError, "private run material"):
            verify(self.archive(extra="xa-mass-product-preview-0.1.0-preview/worker-simulator/config/data/scenario-workers/demo-sim/workers-000.jsonl"), self.frontend)

    def test_rejects_stale_frontend(self):
        archive = self.archive()
        (self.frontend / "static/js/SmsPage-one.js").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "current unified frontend"):
            verify(archive, self.frontend)

    def test_rejects_embedded_independent_frontend(self):
        with self.assertRaisesRegex(ValueError, "separate frontend"):
            verify(self.archive(embedded=True), self.frontend)

    def test_rejects_paths_outside_extracted_root(self):
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            verify(self.archive(extra="xa-mass-product-preview-0.1.0-preview/../outside"), self.frontend)

    def test_rejects_changed_launcher_with_original_manifest(self):
        original = self.archive()
        tampered = self.directory / "tampered.zip"
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(tampered, "w") as target:
            for name in source.namelist():
                target.writestr(name, b"changed" if name.endswith("/run_preview.py") else source.read(name))
        with self.assertRaisesRegex(ValueError, "fingerprint mismatch: run_preview.py"):
            verify(tampered, self.frontend)


if __name__ == "__main__":
    unittest.main()
