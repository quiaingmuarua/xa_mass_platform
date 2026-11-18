"""Check the actual workflow's composition and safe manual dispatch boundary."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/worker-call-performance.yml"


def section(text, name, indent):
    marker = " " * indent + name + ":\n"
    start = text.index(marker) + len(marker)
    lines = text[start:].splitlines(keepends=True)
    result = []
    for line in lines:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        result.append(line)
    return "".join(result)


def job_blocks(text):
    body = text.split("\njobs:\n", 1)[1]
    starts = list(re.finditer(r"^  ([a-z][a-z0-9-]*):\n", body, re.MULTILINE))
    return {match.group(1): body[match.end():starts[index + 1].start() if index + 1 < len(starts) else len(body)]
            for index, match in enumerate(starts)}


def step(block, name):
    marker = "      - name: " + name + "\n"
    start = block.index(marker)
    following = re.search(r"^      - ", block[start + len(marker):], re.MULTILINE)
    end = start + len(marker) + following.start() if following else len(block)
    return block[start:end]


def run_body(block):
    return textwrap.dedent(block.split("        run: |\n", 1)[1])


def expression(value, *, event="workflow_dispatch", ref="refs/heads/main", target="source", baseline="", outcome="success"):
    # Only the small boolean expression subset used by this workflow; no GitHub/network calls.
    value = value.strip().removeprefix("${{").removesuffix("}}").strip()
    names = {"github.event_name": event, "github.ref": ref, "inputs.execution_target": target,
             "inputs.baseline_ref": baseline, "steps.external_run.outcome": outcome}
    for name in sorted(names, key=len, reverse=True):
        value = value.replace(name, repr(names[name]))
    value = value.replace("always()", "True").replace("&&", " and ").replace("||", " or ")
    return eval(value, {"__builtins__": {}}, {})


class ExternalPerformanceWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = job_blocks(cls.text)
        cls.external = cls.jobs["external-performance"]

    def routing(self, **values):
        chosen = {name: bool(expression(re.search(r"^    if: (.+)$", block, re.MULTILINE).group(1), **values))
                  for name, block in self.jobs.items()}
        # The source matrix also retains its ordinary dependency-success admission.
        chosen["lane"] = chosen["lane"] and chosen["lane-build"]
        return {name for name, selected in chosen.items() if selected}

    def test_schedule_and_source_manual_keep_the_original_job_graph(self):
        normal = {"lane-build", "lane", "lane-summary"}
        self.assertEqual(normal, self.routing(event="schedule"))
        self.assertEqual(normal, self.routing(event="schedule", target="external"))
        self.assertEqual(normal, self.routing())
        self.assertEqual({"lane-ab"}, self.routing(baseline="a" * 40))
        self.assertEqual({"lane-ab"}, self.routing(target="source", baseline="a" * 40))
        self.assertIn("needs: lane-build", self.jobs["lane"])
        self.assertIn("needs: lane", self.jobs["lane-summary"])
        self.assertIn("if: always()", self.jobs["lane-summary"])

    def test_external_is_main_manual_only_and_never_runs_source_or_ab_summary(self):
        self.assertEqual({"external-performance"}, self.routing(target="external"))
        self.assertEqual({"external-performance"}, self.routing(target="external", baseline="a" * 40))
        self.assertEqual(set(), self.routing(target="external", ref="refs/heads/feature"))
        self.assertIn("environment: release-preview-load", self.external)
        self.assertIn("runs-on: ubuntu-24.04", self.external)
        self.assertIn("timeout-minutes: 120", self.external)
        self.assertNotIn("setup-java", self.external)
        self.assertNotIn("gradlew", self.external)
        self.assertNotIn("docker", self.external)

    def test_source_defaults_matrix_and_history_are_unchanged(self):
        dispatch = section(self.text, "workflow_dispatch", 2)
        self.assertIn("default: source", section(dispatch, "execution_target", 6))
        self.assertIn("default: 5000", section(dispatch, "external_workers_per_group", 6))
        self.assertIn("default: '1'", section(dispatch, "external_repetitions", 6))
        self.assertIn("default: '3'", section(dispatch, "repetitions", 6))
        self.assertIn("cron: '0 19 * * *'", self.text)
        self.assertEqual(["rate-500", "rate-1000", "rate-2000", "saturation"],
                         re.findall(r"^          - job: (.+)$", self.jobs["lane"], re.MULTILINE))
        self.assertIn("REPETITIONS: ${{ inputs.repetitions || '3' }}", self.jobs["lane"])
        self.assertIn("configs/lane-saturation.json", self.jobs["lane"])
        self.assertIn(":integrations:worker-call-performance:experimentDist", self.jobs["lane-build"])
        self.assertIn("--merge-jobs rate-500,rate-1000,rate-2000,saturation", self.jobs["lane-summary"])
        self.assertIn("if: github.event_name == 'schedule' || inputs.lane_record", self.jobs["lane-summary"])

    def test_concurrency_shares_the_existing_external_environment_without_cancelling(self):
        concurrency = section(self.text, "concurrency", 0)
        value = re.search(r"^  group: (.+)$", concurrency, re.MULTILINE).group(1)
        self.assertEqual("worker-call-performance", expression(value, event="schedule"))
        self.assertEqual("worker-call-performance", expression(value))
        self.assertEqual("release-preview-load", expression(value, target="external"))
        self.assertIn("cancel-in-progress: false", concurrency)
        loaded = (ROOT / ".github/workflows/worker-loaded-recovery.yml").read_text(encoding="utf-8")
        self.assertIn("'release-preview-load'", section(loaded, "concurrency", 0))

    def validate(self, **changes):
        values = {"DIAGNOSTICS": "off", "BASELINE_REF": "", "LANE_CASE": "", "LANE_RECORD": "false",
                  "WORKERS_PER_GROUP": "5000", "REPETITIONS": "1", **changes}
        script = run_body(step(self.external, "Validate external-only options"))
        self.assertTrue(script.startswith("python3 - <<'PY'\n"))
        code = script.split("\n", 1)[1].rsplit("\nPY", 1)[0]
        return subprocess.run([sys.executable, "-c", code], env={**os.environ, **values},
                              text=True, capture_output=True, timeout=10)

    def test_actual_validation_rejects_external_jfr_ab_history_and_unsafe_values(self):
        self.assertEqual(0, self.validate().returncode)
        self.assertEqual(0, self.validate(WORKERS_PER_GROUP="15000", REPETITIONS="3").returncode)
        for changed in ({"DIAGNOSTICS": "jfr"}, {"BASELINE_REF": "a" * 40}, {"LANE_CASE": "task-any-1000"},
                        {"LANE_RECORD": "true"}, {"WORKERS_PER_GROUP": "0"}, {"WORKERS_PER_GROUP": "15001"},
                        {"WORKERS_PER_GROUP": "5000; exit 0"}, {"WORKERS_PER_GROUP": "5000.5"}, {"REPETITIONS": "2"}):
            with self.subTest(changed=changed):
                result = self.validate(**changed)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_external_argv_values_stay_in_environment_and_finalize_only_the_same_run(self):
        run = step(self.external, "Run the published Performance consumer")
        finalize = step(self.external, "Finalize the same external run")
        for selected in (run, finalize):
            body = run_body(selected)
            self.assertNotIn("${{", body)
            self.assertIn('--workers-per-group "$WORKERS_PER_GROUP"', body)
            self.assertIn('--repetitions "$REPETITIONS"', body)
            self.assertIn('--repository "$GITHUB_REPOSITORY"', body)
            self.assertIn("--output-dir build/external-performance", body)
            for binding in ("vars.CONSUMER_SSH_HOST", "vars.CONSUMER_SSH_USER", "vars.CONSUMER_SSH_KNOWN_HOSTS",
                            "secrets.CONSUMER_SSH_PRIVATE_KEY", "vars.PERFORMANCE_DEPLOYMENT_JSON",
                            "vars.PERFORMANCE_CONNECTION_BUDGET_JSON", "secrets.PERFORMANCE_RUNTIME_ACCESS_KEY",
                            "secrets.PERFORMANCE_WORKER_GATEWAY_KEY_ID", "secrets.PERFORMANCE_WORKER_GATEWAY_SECRET",
                            "vars.INSTALLED_RELEASE_TAG", "vars.INSTALLED_RELEASE_COMMIT",
                            "vars.PERFORMANCE_KIT_RELEASE_TAG", "vars.PERFORMANCE_KIT_RELEASE_COMMIT"):
                self.assertIn(binding, selected)
        self.assertIn("--action stop", run_body(finalize))
        guard = re.search(r"^        if: (.+)$", finalize, re.MULTILINE).group(1)
        for outcome in ("success", "failure", "cancelled"):
            self.assertTrue(expression(guard, outcome=outcome))
        self.assertFalse(expression(guard, outcome="skipped"))

    def test_external_artifacts_are_an_explicit_safe_allowlist_outside_source_merge(self):
        upload = step(self.external, "Upload safe external evidence")
        self.assertIn("if: always()", upload)
        self.assertIn("name: external-performance-${{ github.run_attempt }}", upload)
        paths = re.findall(r"^            (build/\S+)$", upload, re.MULTILINE)
        self.assertEqual({"build/external-performance/summary.json", "build/external-performance/summary.md",
                          "build/external-performance/consumption.json", "build/external-performance/orchestration.json"}, set(paths))
        self.assertFalse(any("*" in path or "private" in path or path.endswith(".log") for path in paths))
        self.assertIn("if: always()", step(self.external, "Publish external summary"))
        self.assertNotIn("lane_record", self.external.split("      - uses: actions/checkout", 1)[1])
        self.assertNotIn("perf-lane-data", self.external)
        self.assertIn("pattern: lane-*-${{ github.run_attempt }}", self.jobs["lane-summary"])

    def test_new_controller_uses_existing_jvm_contract_selection(self):
        contract = json.loads((ROOT / ".github/proof-selection-contract.json").read_text(encoding="utf-8"))
        entries = [entry for entry in contract["cases"] if entry["path"] == ".github/scripts/run_external_performance.py"]
        self.assertEqual(1, len(entries))
        self.assertEqual(["jvm_contracts"], entries[0]["lanes"])
        sys.path.insert(0, str(ROOT / ".github/scripts"))
        import check_proof_selection
        self.assertEqual({"jvm_contracts"}, check_proof_selection.selected_lanes(entries[0]["path"], check_proof_selection.load_filters()))

    @unittest.skipIf(os.name == "nt", "The actual workflow Bash invocation runs on Linux")
    def test_actual_workflow_command_passes_quoted_user_values_without_shell_interpretation(self):
        command = run_body(step(self.external, "Run the published Performance consumer"))
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            executable = temporary / "python3"
            capture = temporary / "argv.json"
            executable.write_text("#!" + sys.executable + "\nimport json,os,sys\nfrom pathlib import Path\n"
                                  "Path(os.environ['ARGV_CAPTURE']).write_text(json.dumps(sys.argv[1:]))\n")
            executable.chmod(0o755)
            env = {**os.environ, "PATH": str(temporary) + os.pathsep + os.environ["PATH"],
                   "ARGV_CAPTURE": str(capture), "GITHUB_REPOSITORY": "owner/repo", "WORKERS_PER_GROUP": "5000",
                   "REPETITIONS": "1"}
            result = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command],
                                    env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([".github/scripts/run_external_performance.py", "--repository", "owner/repo",
                              "--workers-per-group", "5000", "--repetitions", "1", "--output-dir", "build/external-performance"],
                             json.loads(capture.read_text()))


if __name__ == "__main__":
    unittest.main()
