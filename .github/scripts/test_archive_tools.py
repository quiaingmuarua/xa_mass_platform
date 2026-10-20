"""Distribution ABI regressions and pure-verification/consumer-build separation."""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from distribution import archive_tools as tools


def zip_bytes(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as bundle:
        for name, value in files.items():
            bundle.writestr(name, value if isinstance(value, bytes) else str(value))
    return output.getvalue()


class ArchiveToolsTest(unittest.TestCase):
    version = "3.0.0"
    commit = "a" * 40

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def archive(self, kind, files):
        path = self.root / f"{kind}.zip"
        prefix = f"xa-mass-{kind}-{self.version}/"
        path.write_bytes(zip_bytes({prefix + name: value for name, value in files.items()}))
        return path

    def runtime_files(self):
        library = zip_bytes({"marker": "fixture"})
        jar = zip_bytes({
            **{f"BOOT-INF/classes/{name}": "fixture" for name in (
                "application.yaml", "application-consumer.yaml", "application-preview.yaml")},
            "BOOT-INF/lib/xa-mass-server-jvm-fixture.jar": library,
        })
        return {
            **{"examples/batch-checks/" + name: ("{}" if name.endswith(".json") else "fixture")
               for name in tools.BATCH_CHECK_EXAMPLES},
            **{"examples/messages/" + name: ("{}" if name.endswith(".json") else "fixture")
               for name in tools.MESSAGES_EXAMPLES},
            f"lib/xa-mass-server-jvm-{self.version}.jar": jar,
            "frontend/dist/index.html": "fixture",
            "frontend/dist/reference/platform-diagnostic-codes.json": json.dumps({
                "schemaVersion": 1, "version": self.version, "gitCommit": self.commit,
                "owners": [{"owner": name} for name in (
                    "server_jvm", "transport:netty-adapter", "transport:worker-core")],
            }),
            "frontend/dist/reference/openapi.json": json.dumps({
                "openapi": "3.1.0", "info": {"title": "XA Mass Runtime API"},
                "paths": {"/api/v1/tasks": {}},
                "tags": [{"name": name} for name in (
                    "Development Users", "Worker Resources", "Tasks", "Runtime View", "Worker Delivery")],
            }),
            "manifest.json": json.dumps({
                "schemaVersion": 6, "version": self.version, "gitCommit": self.commit,
                "serverJar": f"lib/xa-mass-server-jvm-{self.version}.jar", "javaVersion": 21,
                "defaultSpringProfile": "default", "springProfiles": ["default", "consumer", "preview"],
                "frontendIncluded": True,
            }),
            "THIRD_PARTY_NOTICES.md": "fixture", "LICENSE.pure-admin-thin": "fixture",
        }

    def sdk_files(self):
        # The fixture independently names the four publications and their required dependencies.
        artifacts = {
            "xa-mass-worker-delivery-contract": ("jar", [("com.google.code.gson", "gson")]),
            "xa-mass-worker-core": ("jar", [("com.xa.mass", "xa-mass-worker-delivery-contract")]),
            "xa-mass-android-worker": ("aar", [("com.xa.mass", "xa-mass-worker-core"), ("com.squareup.okhttp3", "okhttp")]),
            "xa-mass-android-capability-http": ("aar", [("com.xa.mass", "xa-mass-worker-core"), ("org.nanohttpd", "nanohttpd")]),
        }
        files = {"LICENSE": "fixture", "THIRD_PARTY_NOTICES.md": "fixture", "manifest.json": json.dumps({
            "schemaVersion": 1, "version": self.version, "gitCommit": self.commit, "repository": "repository",
            "artifacts": [{"groupId": "com.xa.mass", "artifactId": name, "packaging": spec[0]}
                          for name, spec in artifacts.items()],
        })}
        for name, (packaging, dependencies) in artifacts.items():
            base = f"repository/com/xa/mass/{name}/{self.version}/{name}-{self.version}"
            files[base + "." + packaging] = b"fixture"
            files[base + "-sources.jar"] = b"fixture"
            files[base + ".pom"] = "<project><dependencies>" + "".join(
                f"<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId></dependency>"
                for group, artifact in dependencies) + "</dependencies></project>"
        return files

    def test_valid_component_archives_and_sdk_build_are_independent(self):
        runtime = self.archive("server-runtime", self.runtime_files())
        sdk = self.archive("worker-sdk", self.sdk_files())
        tools.verify_runtime(runtime, self.version)
        with patch.object(tools, "consume_sdk") as consume:
            self.assertEqual(0, tools.main(["sdk", "--archive", str(sdk), "--version", self.version]))
            consume.assert_not_called()
            self.assertEqual(0, tools.main(["sdk", "--archive", str(sdk), "--version", self.version,
                                            "--consumer-project", str(self.root)]))
            consume.assert_called_once_with(sdk, self.version, self.root)

    def test_runtime_rejects_identity_and_packaging_regressions(self):
        original = self.runtime_files()
        for field, value in (("schemaVersion", 5), ("version", "2.0.0"), ("gitCommit", "short"),
                             ("springProfiles", ["default", "agentforge", "preview"])):
            with self.subTest(field=field):
                files = copy.deepcopy(original)
                manifest = json.loads(files["manifest.json"])
                manifest[field] = value
                files["manifest.json"] = json.dumps(manifest)
                with self.assertRaises(ValueError):
                    tools.verify_runtime(self.archive("server-runtime", files), self.version)
        for filename in ("frontend/dist/index.html", "manifest.json"):
            files = copy.deepcopy(original)
            del files[filename]
            with self.assertRaises(ValueError):
                tools.verify_runtime(self.archive("server-runtime", files), self.version)
        with self.assertRaises(ValueError):
            tools.verify_runtime(self.archive("server-runtime", {**original, "../escaped": "fixture"}), self.version)

    def test_runtime_examples_are_explicit_and_contain_no_saved_state(self):
        original = self.runtime_files()
        for directory, examples in (("batch-checks", tools.BATCH_CHECK_EXAMPLES), ("messages", tools.MESSAGES_EXAMPLES)):
            for name in examples:
                with self.subTest(missing=directory + "/" + name):
                    files = dict(original)
                    del files["examples/" + directory + "/" + name]
                    with self.assertRaisesRegex(ValueError, "examples"):
                        tools.verify_runtime(self.archive("server-runtime", files), self.version)
        for filename in ("configuration.json.lock", "configuration.json.tmp", "data/configuration.json"):
            with self.subTest(runtime_state=filename), self.assertRaisesRegex(ValueError, "runtime data"):
                tools.verify_runtime(self.archive("server-runtime", {**original, "examples/batch-checks/" + filename: "{}"}), self.version)
        with self.assertRaisesRegex(ValueError, "application configuration"):
            tools.verify_runtime(self.archive("server-runtime", {**original, "examples/batch-checks/application.yaml": "fixture"}), self.version)

    def test_sdk_rejects_invalid_identity_missing_sources_and_lost_transitive_dependency(self):
        original = self.sdk_files()
        for field, value in (("version", "2.0.0"), ("gitCommit", "short")):
            files = copy.deepcopy(original)
            manifest = json.loads(files["manifest.json"])
            manifest[field] = value
            files["manifest.json"] = json.dumps(manifest)
            with self.assertRaises(RuntimeError):
                tools.verify_sdk(self.archive("worker-sdk", files), self.version)
        for suffix in ("-sources.jar", ".pom"):
            files = copy.deepcopy(original)
            target = next(name for name in files if name.endswith(suffix))
            if suffix == ".pom":
                files[target] = "<project/>"
            else:
                del files[target]
            with self.assertRaises(RuntimeError):
                tools.verify_sdk(self.archive("worker-sdk", files), self.version)
        with self.assertRaises(RuntimeError):
            tools.verify_sdk(self.archive("worker-sdk", {**original, "../escaped": "fixture"}), self.version)

    def test_corrupt_archives_are_rejected(self):
        archive = self.root / "broken.zip"
        archive.write_bytes(b"not a zip")
        for verify in (tools.verify_runtime, tools.verify_sdk):
            with self.subTest(verify=verify.__name__), self.assertRaises(zipfile.BadZipFile):
                verify(archive, self.version)

    def test_runtime_rejects_duplicate_and_symlink_members(self):
        for duplicate in (True, False):
            with self.subTest(duplicate=duplicate):
                archive = self.archive("server-runtime", self.runtime_files())
                with zipfile.ZipFile(archive, "a") as bundle, warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    name = f"xa-mass-server-runtime-{self.version}/manifest.json"
                    if duplicate:
                        bundle.writestr(name, "{}")
                    else:
                        link = zipfile.ZipInfo(name + ".link")
                        link.create_system = 3
                        link.external_attr = 0o120777 << 16
                        bundle.writestr(link, "../outside")
                with self.assertRaisesRegex(ValueError, "unsafe or duplicate"):
                    tools.verify_runtime(archive, self.version)


if __name__ == "__main__":
    unittest.main()
