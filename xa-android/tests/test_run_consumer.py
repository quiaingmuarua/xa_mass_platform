from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("run_consumer", Path(__file__).resolve().parents[1] / "run_consumer.py")
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


class ConsumerArchiveTest(unittest.TestCase):
    version = "3.0.0"
    commit = "a" * 40

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.runtime = self.archive("server-runtime")
        self.sdk = self.archive("worker-sdk")

    def tearDown(self):
        self.temporary.cleanup()

    def archive(self, component, *, commit=None, version=None, extra=None, missing=None):
        root = f"xa-mass-{component}-{self.version}"
        manifest = {"version": version or self.version, "gitCommit": commit or self.commit}
        files = {}
        if component == "server-runtime":
            manifest.update(schemaVersion=6, javaVersion=21, defaultSpringProfile="default",
                            springProfiles=["default", "consumer", "preview"], frontendIncluded=True,
                            serverJar=f"lib/xa-mass-server-jvm-{self.version}.jar")
            files.update({manifest["serverJar"]: b"jar", "frontend/dist/index.html": b"index",
                          "frontend/dist/reference/openapi.json": b"{}",
                          "frontend/dist/reference/platform-diagnostic-codes.json": b"{}"})
        else:
            manifest.update(schemaVersion=1, repository="repository", artifacts=[
                {"groupId": "com.xa.mass", "artifactId": name, "packaging": packaging}
                for name, packaging in consumer.ARTIFACTS.items()])
            for name, packaging in consumer.ARTIFACTS.items():
                base = f"repository/com/xa/mass/{name}/{self.version}/{name}-{self.version}"
                files.update({f"{base}.{packaging}": name.encode(), f"{base}.pom": b"pom",
                              f"{base}-sources.jar": b"sources"})
        files["manifest.json"] = json.dumps(manifest).encode()
        archive = self.root / f"{component}-{len(list(self.root.glob('*.zip')))}.zip"
        with zipfile.ZipFile(archive, "w") as target:
            for name, payload in files.items():
                if name != missing:
                    target.writestr(root + "/" + name, payload)
            if extra:
                target.writestr(*extra)
        return archive

    def prepare(self, *, runtime=None, sdk=None, runtime_hash=None, work=None):
        runtime = runtime or self.runtime
        sdk = sdk or self.sdk
        return consumer.prepare_archives(runtime, sdk, self.version, self.commit,
                                         runtime_hash or consumer.digest(runtime), consumer.digest(sdk),
                                         work or self.root / "installation")

    def test_verified_pair_can_be_reused_and_builds_one_public_server_command(self):
        receipt = self.prepare()
        self.assertEqual(receipt, self.prepare())
        command = consumer.server_command(receipt, "profile_example")
        self.assertIn("--spring.profiles.active=consumer", command)
        self.assertIn("--xa.mass.project-assembly.projects[0].project-id=default", command)
        self.assertIn("--xa.mass.kernel-pacer.preset=DEFAULT", command)
        self.assertEqual(4, len(receipt["sdkArtifacts"]))
        self.assertFalse(any("scenario-workers" in argument for argument in command))
        self.assertTrue(Path(receipt["sdkRoot"], "consumer-origin.json").is_file())

    def test_mismatch_is_rejected_before_installation(self):
        for runtime in [self.archive("server-runtime", commit="b" * 40),
                        self.archive("server-runtime", version="2.0.0")]:
            with self.subTest(runtime=runtime), self.assertRaisesRegex(ValueError, "identity"):
                self.prepare(runtime=runtime)
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.prepare(runtime_hash="0" * 64)
        self.assertFalse((self.root / "installation").exists())

    def test_missing_binaries_and_corrupt_zip_are_rejected(self):
        runtime = self.archive("server-runtime", missing=f"lib/xa-mass-server-jvm-{self.version}.jar")
        with self.assertRaisesRegex(ValueError, "missing required"):
            self.prepare(runtime=runtime)
        sdk = self.archive("worker-sdk", missing=("repository/com/xa/mass/xa-mass-android-worker/"
                            + self.version + "/xa-mass-android-worker-" + self.version + ".aar"))
        with self.assertRaisesRegex(ValueError, "missing required"):
            self.prepare(sdk=sdk)
        broken = self.root / "broken.zip"
        broken.write_bytes(b"invalid archive")
        with self.assertRaises(zipfile.BadZipFile):
            self.prepare(runtime=broken)

    def test_unsafe_duplicate_and_symlink_paths_are_rejected(self):
        symlink = zipfile.ZipInfo(f"xa-mass-server-runtime-{self.version}/link")
        symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        fifo = zipfile.ZipInfo(f"xa-mass-server-runtime-{self.version}/fifo")
        fifo.external_attr = (stat.S_IFIFO | 0o600) << 16
        for extra in [("../outside", b"escape"), ("C:/outside", b"escape"),
                      (f"xa-mass-server-runtime-{self.version}/../outside", b"escape"),
                      (f"xa-mass-server-runtime-{self.version}/MANIFEST.JSON", b"duplicate"),
                      (symlink, b"target"), (fifo, b"")]:
            with self.subTest(extra=str(extra[0])), self.assertRaises(ValueError):
                self.prepare(runtime=self.archive("server-runtime", extra=extra))

    def test_changed_installation_is_never_silently_reused(self):
        receipt = self.prepare()
        Path(receipt["serverJar"]).write_bytes(b"different")
        with self.assertRaisesRegex(ValueError, "different bytes"):
            self.prepare()
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            consumer.server_command(receipt, "profile_example")

    def test_unowned_or_different_inputs_are_rejected(self):
        occupied = self.root / "occupied"
        occupied.mkdir()
        (occupied / "other.txt").write_text("keep")
        with self.assertRaisesRegex(ValueError, "not an empty"):
            self.prepare(work=occupied)
        self.prepare()
        different = self.archive("server-runtime", extra=(f"xa-mass-server-runtime-{self.version}/extra.txt", b"new"))
        with self.assertRaisesRegex(ValueError, "different consumer inputs"):
            self.prepare(runtime=different)

    def test_reuse_rejects_unexpected_content_and_changed_sdk_origin(self):
        receipt = self.prepare()
        unexpected = self.root / "installation/foreign.txt"
        unexpected.write_text("preserve")
        with self.assertRaisesRegex(ValueError, "unexpected"):
            self.prepare()
        self.assertEqual("preserve", unexpected.read_text())
        unexpected.unlink()
        Path(receipt["sdkRoot"], "consumer-origin.json").write_text('{}')
        with self.assertRaisesRegex(ValueError, "SDK origin"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
