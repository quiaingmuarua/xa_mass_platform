"""Verify SMS Preview uses the current unified Console build and one Server JAR."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import zipfile


def verify(archive: Path, frontend: Path) -> dict:
    root = "sms-reception-0.1.0-preview"
    prefix = root + "/frontend/dist/"
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if not names or len(names) != len(set(names)) or any(
                "\\" in name or ".." in PurePosixPath(name).parts
                or PurePosixPath(name).parts[0] != root for name in names):
            raise ValueError("Invalid Preview archive paths")
        for required in ("run_preview.py", "run_acceptance.py", "requirements.txt", "preview-manifest.json"):
            if root + "/" + required not in names:
                raise ValueError("Missing packaged launcher: " + required)
        if not any(name.startswith(root + "/scenario-workers/lib/") and name.endswith(".jar") for name in names):
            raise ValueError("Missing Scenario Worker Host")
        # The generated dictionary is supplied by distribution after the frontend build.
        dictionary = "reference/platform-diagnostic-codes.json"
        expected = {path.relative_to(frontend).as_posix(): path.read_bytes()
                    for path in frontend.rglob("*") if path.is_file()
                    and path.relative_to(frontend).as_posix() != dictionary}
        observed = {name[len(prefix):]: bundle.read(name) for name in names
                    if name.startswith(prefix) and not name.endswith("/")
                    and name[len(prefix):] != dictionary}
        if "index.html" not in expected or observed != expected:
            raise ValueError("Preview frontend differs from the current unified build")
        if not any(name.startswith("static/js/SmsPage-") for name in observed):
            raise ValueError("Missing lazy SMS page")
        if any("node_modules" in PurePosixPath(name).parts or name.endswith(("/package.json", "/gradlew"))
               for name in names):
            raise ValueError("Preview contains a frontend source toolchain")
        manifest = json.loads(bundle.read(root + "/preview-manifest.json"))
        server = manifest["artifacts"]["server"]
        server_bytes = bundle.read(root + "/lib/" + server["file"])
        if hashlib.sha256(server_bytes).hexdigest() != server["sha256"]:
            raise ValueError("Server artifact fingerprint differs from manifest")
        with zipfile.ZipFile(io.BytesIO(server_bytes)) as jar:
            if any(name.startswith("BOOT-INF/classes/sms-frontend/") for name in jar.namelist()):
                raise ValueError("Server JAR contains retired independent SMS frontend")
    return {"status": "passed", "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "unifiedFrontendFiles": len(expected), "embeddedSmsFrontend": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--frontend", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.frontend), indent=2))
