"""Execute the workflow's report publication against an isolated local remote."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
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

    def publish(self, existing: dict[str, bytes], *, status="passed", damage=None) -> dict[str, bytes]:
        workflow = (ROOT / ".github/workflows/worker-call-performance.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Record this run on the data branch\n", 1)[1]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1]
                                .split("      - name: Publish merged summary\n", 1)[0])
        windows_bash = Path("C:/Program Files/Git/bin/bash.exe")
        bash = str(windows_bash) if os.name == "nt" and windows_bash.is_file() else shutil.which("bash")
        self.assertIsNotNone(bash, "Report publication contract requires Bash")
        build = ROOT / "build"
        build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="lane-report-", dir=build) as temporary:
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
            # A source checkout's deployment configuration must not be changed.
            application = workspace / "frontend/vercel.json"
            application.parent.mkdir()
            application.write_text('{"buildCommand":"pnpm run build:demo"}', encoding="utf-8")
            environment = dict(os.environ, GITHUB_WORKSPACE=workspace.as_posix(),
                               GITHUB_RUN_ID="12345", GITHUB_RUN_ATTEMPT="1", GITHUB_REF_NAME="main",
                               GITHUB_SHA="a" * 40, GITHUB_EVENT_NAME="schedule")
            if os.name != "nt":
                # setup-python supplies this name to the workflow; use this
                # test interpreter even on Linux installations with only python3.
                executables = workspace / "bin"
                executables.mkdir()
                (executables / "python").symlink_to(sys.executable)
                environment["PATH"] = str(executables) + os.pathsep + environment.get("PATH", "")
            # Run the real merge, including its nonzero exit, before extracting
            # the exact publication script from the workflow. No fallback record
            # is created by the test or the publisher.
            jobs = workspace / "build/lane-jobs"
            for job, case, case_status in (("rate-500", "task-any-500", "passed"),
                                            ("saturation", "sat-task-any", "invalid" if status == "failed" else "passed")):
                path = jobs / f"lane-{job}-1/lane-summary.json"
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps({"status": "failed" if case_status == "invalid" else "passed",
                                            "configurationId": "fixture-" + job, "referenceHost": True,
                                            "cases": [{"case": case, "repetition": 1,
                                                       "mode": "saturation" if job == "saturation" else "open",
                                                       "path": "task-any", "rate": 500 if job == "rate-500" else None,
                                                       "status": case_status, "completedPerSecond": 500,
                                                       "invalidReasons": ["fixture-sampling-failure"] if case_status == "invalid" else []}]}),
                                encoding="utf-8")
            merged = workspace / "build/worker-call-performance-proof/lane-merged"
            merge = subprocess.run([sys.executable, str(ROOT / "integrations/worker-call-performance/run_worker_call_performance.py"),
                                    "--merge-lane", str(jobs), "--merge-jobs", "rate-500,saturation",
                                    "--output-root", str(merged)], env=environment,
                                   capture_output=True, text=True, timeout=30)
            self.assertEqual(1 if status == "failed" else 0, merge.returncode, merge.stdout + merge.stderr)
            self.assertEqual(status, json.loads(merge.stdout)["status"], merge.stdout + merge.stderr)
            record = merged / "evidence/run-record.json"
            generated = record.read_bytes()
            if damage == "missing":
                record.unlink()
            elif damage == "malformed":
                record.write_text("{", encoding="utf-8")
            elif damage == "identity":
                other_run = json.loads(generated)
                other_run["runId"] = "different-run"
                record.write_text(json.dumps(other_run), encoding="utf-8")
            elif damage == "shape":
                record.write_text("[]", encoding="utf-8")
            before = self.git(remote, "rev-parse", "perf-lane-data")
            result = subprocess.run([bash, "-e", "-o", "pipefail", "-c", script], cwd=workspace,
                                    env=environment, capture_output=True, text=True, timeout=30)
            if damage:
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(before, self.git(remote, "rev-parse", "perf-lane-data"))
            else:
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual({"buildCommand": "pnpm run build:demo"}, json.loads(application.read_text()))
            names = self.git(remote, "ls-tree", "-r", "--name-only", "perf-lane-data").splitlines()
            published = {name: subprocess.check_output(["git", "-C", str(remote), "show", f"perf-lane-data:{name}"])
                         for name in names}
            if not damage:
                current = [content for name, content in published.items() if name.endswith("-12345-1.json")]
                self.assertEqual([json.loads(generated)], [json.loads(content) for content in current],
                                 "Publication must preserve the generated identity, statuses and metrics")
            return published

    def test_first_report_disables_deployment_without_application_source(self):
        published = self.publish({})
        self.assertEqual({"git": {"deploymentEnabled": False}},
                         json.loads(published.pop("frontend/vercel.json")))
        self.assertEqual(1, len(published))
        name, content = next(iter(published.items()))
        self.assertRegex(name, r"^runs/\d{8}T\d{6}Z-12345-1\.json$")
        self.assertEqual("12345", json.loads(content)["runId"])
        self.assertEqual("passed", json.loads(content)["status"])

    def test_existing_reports_are_preserved_and_deployment_marker_is_repaired(self):
        retained = {"runs/old.json": b'{"runId":"old"}\n',
                    "external-release-load/closeout.md": b"Historical acceptance evidence.\n"}
        published = self.publish({**retained, "frontend/vercel.json": b'{"git":{"deploymentEnabled":true}}'})
        for name, content in retained.items():
            self.assertEqual(content, published[name])
        self.assertEqual({"git": {"deploymentEnabled": False}}, json.loads(published["frontend/vercel.json"]))
        self.assertEqual(2, len([name for name in published if name.startswith("runs/")]))

    def test_failed_merge_publishes_original_status_and_excludes_invalid_metrics(self):
        published = self.publish({}, status="failed")
        record = json.loads(next(content for name, content in published.items() if name.startswith("runs/")))
        self.assertEqual("failed", record["status"])
        self.assertEqual({"passed": 1}, record["cases"]["task-any-500"]["statuses"])
        self.assertEqual({"invalid": 1}, record["cases"]["sat-task-any"]["statuses"])
        self.assertTrue(all(value is None for value in record["cases"]["sat-task-any"]["metrics"].values()))

    def test_missing_malformed_and_other_run_records_never_change_the_remote(self):
        for damage in ("missing", "malformed", "identity", "shape"):
            with self.subTest(damage=damage):
                published = self.publish({"runs/old.json": b'{"runId":"old"}\n'}, status="failed", damage=damage)
                self.assertEqual({"runs/old.json": b'{"runId":"old"}\n'}, published)


if __name__ == "__main__":
    unittest.main()
