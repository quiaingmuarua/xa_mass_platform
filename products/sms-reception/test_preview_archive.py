from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_preview_archive import verify


class PreviewArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.frontend = self.directory / "frontend"
        (self.frontend / "static/js").mkdir(parents=True)
        (self.frontend / "index.html").write_text("console", encoding="utf-8")
        (self.frontend / "static/js/SmsPage-one.js").write_text("sms", encoding="utf-8")

    def archive(self, *, embedded=False, extra=None):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as bundle:
            bundle.writestr("BOOT-INF/classes/Application.class", b"server")
            if embedded:
                bundle.writestr("BOOT-INF/classes/sms-frontend/index.html", b"old")
        path = self.directory / "preview.zip"
        root = "sms-reception-0.1.0-preview/"
        with zipfile.ZipFile(path, "w") as bundle:
            for name in ("run_preview.py", "run_acceptance.py", "requirements.txt"):
                bundle.writestr(root + name, "launcher")
            bundle.writestr(root + "scenario-workers/lib/host.jar", b"host")
            bundle.writestr(root + "lib/server.jar", jar.getvalue())
            bundle.writestr(root + "preview-manifest.json", json.dumps({"artifacts": {"server": {
                "file": "server.jar", "sha256": hashlib.sha256(jar.getvalue()).hexdigest()}}}))
            for asset in self.frontend.rglob("*"):
                if asset.is_file():
                    bundle.write(asset, root + "frontend/dist/" + asset.relative_to(self.frontend).as_posix())
            if extra:
                bundle.writestr(extra, "unexpected")
        return path

    def test_accepts_current_unified_build(self):
        self.assertEqual("passed", verify(self.archive(), self.frontend)["status"])

    def test_rejects_stale_frontend(self):
        archive = self.archive()
        (self.frontend / "static/js/SmsPage-one.js").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            verify(archive, self.frontend)

    def test_rejects_embedded_independent_frontend(self):
        with self.assertRaisesRegex(ValueError, "retired"):
            verify(self.archive(embedded=True), self.frontend)

    def test_rejects_paths_outside_extracted_root(self):
        with self.assertRaisesRegex(ValueError, "paths"):
            verify(self.archive(extra="sms-reception-0.1.0-preview/../outside"), self.frontend)


if __name__ == "__main__":
    unittest.main()
