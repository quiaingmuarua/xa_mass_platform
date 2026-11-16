"""Execute the workflow's report publication against an isolated local remote."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ReportPublicationTest(unittest.TestCase):
    def git(self, directory: Path, *arguments: str) -> str:
        result = subprocess.run(["git", "-C", str(directory), *arguments],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def publish(self, existing: dict[str, bytes]) -> dict[str, bytes]:
        workflow = (ROOT / ".github/workflows/worker-call-performance.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Record this run on the data branch\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1]
                                .split("      - name: Publish merged summary\n", 1)[0])
        windows_bash = Path("C:/Program Files/Git/bin/bash.exe")
        bash = str(windows_bash) if os.name == "nt" and windows_bash.is_file() else shutil.which("bash")
        self.assertIsNotNone(bash, "Report publication contract requires Bash")
        with tempfile.TemporaryDirectory(prefix="lane-report-") as temporary:
            workspace = Path(temporary)
            remote = workspace / "remote.git"
            remote.mkdir()
            self.git(remote, "init", "--bare", "--quiet")
            data = workspace / "build/lane-data"
            data.mkdir(parents=True)
            # The workflow's existing checkout/orphan setup precedes the changed
            # publication section. Use an ordinary clone fixture, not a worktree.
            self.git(data, "init", "--quiet")
            self.git(data, "config", "user.name", "Publication fixture")
            self.git(data, "config", "user.email", "fixture@example.invalid")
            self.git(data, "config", "commit.gpgsign", "false")
            self.git(data, "remote", "add", "origin", str(remote))
            for relative, content in existing.items():
                path = data / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            self.git(data, "add", ".")
            self.git(data, "commit", "--allow-empty", "--quiet", "-m", "Existing evidence")
            self.git(data, "push", "--quiet", "origin", "HEAD:refs/heads/perf-lane-data")
            record = workspace / "build/worker-call-performance-proof/lane-merged/evidence/run-record.json"
            record.parent.mkdir(parents=True)
            record.write_text('{"runId":"12345","status":"passed"}\n', encoding="utf-8")
            # A source checkout's deployment configuration must not be changed.
            application = workspace / "frontend/vercel.json"
            application.parent.mkdir()
            application.write_text('{"buildCommand":"pnpm run build:demo"}', encoding="utf-8")
            environment = dict(os.environ, GITHUB_WORKSPACE=workspace.as_posix(),
                               GITHUB_RUN_ID="12345", GITHUB_RUN_ATTEMPT="1", GITHUB_REF_NAME="main")
            result = subprocess.run([bash, "-e", "-o", "pipefail", "-c", script], cwd=workspace,
                                    env=environment, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual({"buildCommand": "pnpm run build:demo"}, json.loads(application.read_text()))
            names = self.git(remote, "ls-tree", "-r", "--name-only", "perf-lane-data").splitlines()
            return {name: subprocess.check_output(["git", "-C", str(remote), "show", f"perf-lane-data:{name}"])
                    for name in names}

    def test_first_report_disables_deployment_without_application_source(self):
        published = self.publish({})
        self.assertEqual({"git": {"deploymentEnabled": False}},
                         json.loads(published.pop("frontend/vercel.json")))
        self.assertEqual(1, len(published))
        name, content = next(iter(published.items()))
        self.assertRegex(name, r"^runs/\d{8}T\d{6}Z-12345-1\.json$")
        self.assertEqual({"runId": "12345", "status": "passed"}, json.loads(content))

    def test_existing_reports_are_preserved_and_deployment_marker_is_repaired(self):
        retained = {"runs/old.json": b'{"runId":"old"}\n',
                    "external-release-load/closeout.md": b"Historical acceptance evidence.\n"}
        published = self.publish({**retained, "frontend/vercel.json": b'{"git":{"deploymentEnabled":true}}'})
        for name, content in retained.items():
            self.assertEqual(content, published[name])
        self.assertEqual({"git": {"deploymentEnabled": False}}, json.loads(published["frontend/vercel.json"]))
        self.assertEqual(2, len([name for name in published if name.startswith("runs/")]))


if __name__ == "__main__":
    unittest.main()
