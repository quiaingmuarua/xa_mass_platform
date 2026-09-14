from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_archive import verify


CONFIGURATIONS = {name: (name + "\n").encode() for name in (
    "application.yaml", "application-scenario-workers.yaml", "application-agentforge.yaml", "application-preview.yaml")}


def library(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return output.getvalue()


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

    def archive(self, *, embedded=False, extra=None, missing_configuration=None,
                dependency_configuration=None, scenario_configuration=None,
                host_test_configuration=False, different_external_configuration=False):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as bundle:
            bundle.writestr("BOOT-INF/classes/Application.class", b"server")
            scenario_entries = {"Messages.class": b"messages"}
            if scenario_configuration:
                scenario_entries[scenario_configuration] = b"stale"
            bundle.writestr("BOOT-INF/lib/xa-mass-message-campaigns-test.jar", library(scenario_entries))
            server_entries = {"Server.class": b"platform"}
            if dependency_configuration:
                server_entries[dependency_configuration] = b"stale"
            bundle.writestr("BOOT-INF/lib/xa-mass-server-jvm-test.jar", library(server_entries))
            for name, data in CONFIGURATIONS.items():
                if name != missing_configuration:
                    bundle.writestr("BOOT-INF/classes/" + name, data)
            if host_test_configuration:
                bundle.writestr("BOOT-INF/classes/application-test.yaml", b"test")
            if embedded:
                bundle.writestr("BOOT-INF/classes/sms-frontend/index.html", b"old")
        path = self.directory / "preview.zip"
        root = "xa-mass-scenario-preview-0.1.0-preview/"
        files = {name: b"launcher" for name in (
            "run_preview.py", "requirements.txt", "README.md", "config/application-preview.yaml")}
        files["config/application-preview.yaml"] = (b"different" if different_external_configuration
                else CONFIGURATIONS["application-preview.yaml"])
        files["worker-simulator/lib/xa-mass-worker-simulator-test.jar"] = b"host"
        for name in ("lab", "sms", "messages", "products"):
            files["worker-simulator/config/" + name + ".json"] = b"{}"
        files["lib/server.jar"] = jar.getvalue()
        for asset in self.frontend.rglob("*"):
            if asset.is_file():
                files["frontend/dist/" + asset.relative_to(self.frontend).as_posix()] = asset.read_bytes()
        files["preview-manifest.json"] = json.dumps({
            "scenarios": ["sms", "messages"], "processModel": ["server", "host"], "gitCommit": "a" * 40,
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

    def test_requires_every_packaged_host_configuration(self):
        for name in CONFIGURATIONS:
            with self.subTest(configuration=name), self.assertRaisesRegex(ValueError, "missing or unexpected"):
                verify(self.archive(missing_configuration=name), self.frontend)

    def test_rejects_application_configuration_in_platform_dependency(self):
        for name in ("application.yaml", "application-test.yaml", "application-integration-test.properties"):
            with self.subTest(configuration=name), self.assertRaisesRegex(ValueError, "dependency contains"):
                verify(self.archive(dependency_configuration=name), self.frontend)

    def test_rejects_test_configuration_in_host(self):
        with self.assertRaisesRegex(ValueError, "missing or unexpected"):
            verify(self.archive(host_test_configuration=True), self.frontend)

    def test_rejects_application_configuration_in_scenario_dependency(self):
        with self.assertRaisesRegex(ValueError, "dependency contains"):
            verify(self.archive(scenario_configuration="application-preview.yaml"), self.frontend)

    def test_rejects_test_configuration_in_archive(self):
        with self.assertRaisesRegex(ValueError, "unexpected external"):
            verify(self.archive(extra="xa-mass-scenario-preview-0.1.0-preview/config/application-test.yaml"), self.frontend)

    def test_external_preview_must_match_host_source(self):
        with self.assertRaisesRegex(ValueError, "differs from the packaged"):
            verify(self.archive(different_external_configuration=True), self.frontend)

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
            verify(self.archive(extra="xa-mass-scenario-preview-0.1.0-preview/worker-simulator/config/data/scenario-workers/demo-sim/workers-000.jsonl"), self.frontend)

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
            verify(self.archive(extra="xa-mass-scenario-preview-0.1.0-preview/../outside"), self.frontend)

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
