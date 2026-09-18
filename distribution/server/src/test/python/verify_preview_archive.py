"""Validate the fresh shared Preview archive, fingerprints and unified frontend."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import zipfile


_runtime_verifier = Path(__file__).with_name("verify_runtime_archive.py")
_spec = importlib.util.spec_from_file_location("runtime_archive_configuration", _runtime_verifier)
_runtime = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_runtime)


def verify(archive, frontend):
    root = "xa-mass-scenario-preview-0.1.0-preview"
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if not names or len(names) != len(set(names)) or any("\\" in name or ".." in PurePosixPath(name).parts
                or PurePosixPath(name).parts[0] != root for name in names):
            raise ValueError("Unsafe or duplicate archive member")
        files = {name[len(root) + 1:]: bundle.read(name) for name in names if not name.endswith("/")}
        if any(_runtime.is_application_configuration(name) and name != "config/application-preview.yaml" for name in files):
            raise ValueError("Preview contains unexpected external application configuration")
        required = {"run_preview.py", "requirements.txt", "README.md", "preview-manifest.json", "config/application-preview.yaml"}
        required.update("worker-simulator/config/" + name + ".json" for name in ("lab", "sms", "messages", "products"))
        if not required <= files.keys():
            raise ValueError("Incomplete Preview entrypoints")
        manifest = json.loads(files["preview-manifest.json"])
        if manifest["scenarios"] != ["sms", "messages", "app-checks"] or manifest["processModel"] != ["server", "host"]:
            raise ValueError("Wrong default composition")
        if len(manifest["gitCommit"]) != 40:
            raise ValueError("Missing build identity")
        for name, fingerprint in manifest["sha256"].items():
            if hashlib.sha256(files[name]).hexdigest() != fingerprint:
                raise ValueError("Artifact fingerprint mismatch: " + name)
        prefix = "frontend/dist/"
        expected = {path.relative_to(frontend).as_posix(): path.read_bytes() for path in frontend.rglob("*") if path.is_file()}
        actual = {name[len(prefix):]: data for name, data in files.items() if name.startswith(prefix)}
        dictionary = "reference/platform-diagnostic-codes.json"
        if dictionary not in actual or json.loads(actual[dictionary])["gitCommit"] != manifest["gitCommit"]:
            raise ValueError("Missing current-build diagnostic dictionary")
        expected.pop(dictionary, None)
        actual.pop(dictionary)
        if "index.html" not in expected or actual != expected:
            raise ValueError("Preview does not contain the current unified frontend")
        for component in ("SmsPage-", "MessagePage-"):
            if not any(name.startswith("static/js/" + component) for name in actual):
                raise ValueError("Missing lazy product page")
        jars = [name for name in files if name.startswith("lib/") and name.endswith(".jar")]
        if len(jars) != 1:
            raise ValueError("Expected one Server JAR")
        if not any(name.startswith("worker-simulator/lib/xa-mass-worker-simulator-") for name in files):
            raise ValueError("Missing actual Worker Simulator")
        if any("node_modules" in PurePosixPath(name).parts or "private" in PurePosixPath(name).parts
                or name.endswith((".jsonl", "package.json", "gradlew", "build.gradle", ".log")) for name in files):
            raise ValueError("Source toolchain or private run material in Preview")
        with zipfile.ZipFile(io.BytesIO(files[jars[0]])) as jar:
            _runtime.verify_server_configuration(jar)
            if jar.read("BOOT-INF/classes/application-preview.yaml") != files["config/application-preview.yaml"]:
                raise ValueError("External preview configuration differs from the packaged host configuration")
            entries = jar.namelist()
            if any("sms-frontend/" in name or "worker-simulator" in name for name in entries):
                raise ValueError("Server embeds a separate frontend or Worker Simulator")
            if not any("xa-mass-message-campaigns-" in name for name in entries):
                raise ValueError("Messages backend absent")
            if not any("xa-mass-app-checks-" in name for name in entries):
                raise ValueError("App checks backend absent")
    return {"passed": True, "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "fingerprintedFiles": len(manifest["sha256"]), "unifiedFrontendFiles": len(actual),
            "gitCommit": manifest["gitCommit"], "scenarios": manifest["scenarios"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--frontend", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(verify(args.archive, args.frontend), indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result, encoding="utf-8")
    print(result)
