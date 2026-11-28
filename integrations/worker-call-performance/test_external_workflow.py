"""Check shared external execution, bounded observation routing and unchanged source CI."""
from __future__ import annotations

import json
from datetime import date, datetime, time, timedelta, timezone
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
CONSUMER = ROOT / ".github/workflows/external-performance-consumer.yml"
OBSERVATION = ROOT / ".github/workflows/external-performance-observation.yml"


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


def expression(value, *, event="workflow_dispatch", ref="refs/heads/main", target="source", baseline="", outcome="success",
               merge_outcome="success", record=False, cancelled=False, observation_plan="", admitted="false", attempt=1):
    # Only the small boolean expression subset used by this workflow; no GitHub/network calls.
    value = value.strip().removeprefix("${{").removesuffix("}}").strip()
    names = {"github.event_name": event, "github.ref": ref, "github.run_attempt": attempt, "inputs.execution_target": target,
             "inputs.baseline_ref": baseline, "steps.external_run.outcome": outcome,
             "steps.merge.outcome": merge_outcome, "inputs.lane_record": record,
             "inputs.observation_plan": observation_plan, "steps.external_run.outputs.observation_admitted": admitted}
    for name in sorted(names, key=len, reverse=True):
        value = value.replace(name, repr(names[name]))
    value = value.replace("!cancelled()", repr(not cancelled)).replace("always()", "True")
    value = value.replace("&&", " and ").replace("||", " or ")
    return eval(value, {"__builtins__": {}}, {})


class ExternalPerformanceWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = job_blocks(cls.text)
        cls.consumer = CONSUMER.read_text(encoding="utf-8")
        cls.observation = OBSERVATION.read_text(encoding="utf-8")
        cls.external = job_blocks(cls.consumer)["external-performance"]
        cls.plan = json.loads((ROOT / ".github/external-performance-observation.json").read_text(encoding="utf-8"))

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

    def test_manual_external_and_schedule_call_one_consumer_with_the_same_environment(self):
        caller = self.jobs["external-performance"]
        self.assertIn("uses: ./.github/workflows/external-performance-consumer.yml", caller)
        self.assertIn("secrets: inherit", caller)
        self.assertNotIn("steps:", caller)
        # Dispatch/API quantities are strings; the reusable contract requires a number.
        for binding in ("workers_per_group: ${{ fromJSON(inputs.external_workers_per_group) }}",
                        "repetitions: ${{ inputs.external_repetitions }}",
                        "diagnostics: ${{ inputs.diagnostics }}", "baseline_ref: ${{ inputs.baseline_ref }}",
                        "lane_case: ${{ inputs.lane_case }}", "lane_record: ${{ inputs.lane_record }}"):
            self.assertIn(binding, caller)
        self.assertNotIn("observation_plan:", caller)
        self.assertNotIn("artifact_retention_days:", caller)
        self.assertIn("type: number", section(section(self.consumer, "workflow_call", 2), "workers_per_group", 6))
        triggers = self.consumer.split("permissions:", 1)[0]
        self.assertIn("  workflow_call:", triggers)
        self.assertNotIn("  workflow_dispatch:", triggers)
        self.assertNotIn("  schedule:", triggers)
        inputs = section(self.consumer, "workflow_call", 2)
        for field, expected in (("diagnostics", "default: 'off'"), ("baseline_ref", "default: ''"),
                                ("lane_case", "default: ''"), ("lane_record", "default: false"),
                                ("observation_plan", "default: ''"), ("artifact_retention_days", "default: 7")):
            self.assertIn(expected, section(inputs, field, 6))

    def test_observation_has_seven_explicit_dates_and_cannot_queue_behind_manual_work(self):
        # Cross-file contract: derive cron independently from the real window, not a copied roster.
        plan = self.plan
        self.assertEqual((1, 7, "05:30", "+08:00", 5000, 1),
                         tuple(plan[key] for key in ("schemaVersion", "days", "localTime", "utcOffset", "workersPerGroup", "repetitions")))
        start = date.fromisoformat(plan["startDate"])
        self.assertEqual("external-10k-" + start.strftime("%Y%m%d"), plan["windowId"])
        hour, minute = map(int, plan["localTime"].split(":"))
        offset = timedelta(hours=int(plan["utcOffset"][1:3]), minutes=int(plan["utcOffset"][4:6]))
        local = timezone(offset if plan["utcOffset"].startswith("+") else -offset)
        dates = [datetime.combine(start + timedelta(days=day), time(hour, minute), local).astimezone(timezone.utc)
                 for day in range(plan["days"])]
        expected = [f"{stamp.minute} {stamp.hour} {stamp.day} {stamp.month} *" for stamp in dates]
        self.assertEqual(expected, re.findall(r"^    - cron: '([^']+)'$", self.observation, re.MULTILINE))
        self.assertNotIn("workflow_dispatch:", self.observation)
        self.assertNotIn("concurrency:", self.observation)
        self.assertNotIn("concurrency:", self.consumer)
        self.assertEqual({"external-performance"}, set(job_blocks(self.observation)))
        caller = job_blocks(self.observation)["external-performance"]
        self.assertIn("github.ref == 'refs/heads/main' && github.event_name == 'schedule'", caller)
        for binding in ("uses: ./.github/workflows/external-performance-consumer.yml", "secrets: inherit",
                        f"workers_per_group: {plan['workersPerGroup']}", f"repetitions: '{plan['repetitions']}'",
                        "observation_plan: .github/external-performance-observation.json", "artifact_retention_days: 30"):
            self.assertIn(binding, caller)
        self.assertNotIn("run_external_performance.py", self.observation)
        self.assertNotIn("workflow_dispatch", self.consumer)

    def test_source_workload_defaults_and_matrix_are_unchanged(self):
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
        merge = step(self.jobs["lane-summary"], "Merge lane jobs and compare with history")
        self.assertIn("id: merge", merge)
        self.assertNotIn("continue-on-error", merge)

    def test_selected_history_records_survive_failed_merge_without_cancellation_or_routing_changes(self):
        publication = step(self.jobs["lane-summary"], "Record this run on the data branch")
        guard = re.search(r"^        if: (.+)$", publication, re.MULTILINE).group(1)
        for outcome in ("success", "failure"):
            with self.subTest(outcome=outcome):
                self.assertTrue(expression(guard, event="schedule", merge_outcome=outcome))
                self.assertTrue(expression(guard, record=True, merge_outcome=outcome))
                self.assertFalse(expression(guard, merge_outcome=outcome))
                self.assertFalse(expression(guard, record=True, merge_outcome=outcome, cancelled=True))
        for outcome in ("skipped", "cancelled"):
            self.assertFalse(expression(guard, record=True, merge_outcome=outcome))

    def test_external_concurrency_is_independent_of_source_loaded_recovery_without_cancelling(self):
        concurrency = section(self.text, "concurrency", 0)
        value = re.search(r"^  group: (.+)$", concurrency, re.MULTILINE).group(1)
        self.assertEqual("worker-call-performance", expression(value, event="schedule"))
        self.assertEqual("worker-call-performance", expression(value))
        self.assertEqual("release-preview-load", expression(value, target="external"))
        self.assertIn("cancel-in-progress: false", concurrency)
        loaded = (ROOT / ".github/workflows/worker-loaded-recovery.yml").read_text(encoding="utf-8")
        self.assertNotIn("release-preview-load", section(loaded, "concurrency", 0))

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
            self.assertIn('if [ -n "$OBSERVATION_PLAN" ]', body)
            self.assertIn('--observation-plan "$OBSERVATION_PLAN"', body)
            self.assertIn('"${observation[@]}"', body)
            self.assertIn("OBSERVATION_PLAN: ${{ inputs.observation_plan }}", selected)
            self.assertIn("OBSERVATION_SCHEDULE: ${{ github.event.schedule }}", selected)
            for binding in ("vars.CONSUMER_SSH_HOST", "vars.CONSUMER_SSH_USER", "vars.CONSUMER_SSH_KNOWN_HOSTS",
                            "secrets.CONSUMER_SSH_PRIVATE_KEY"):
                self.assertIn(binding, selected)
        for binding in ("vars.PERFORMANCE_DEPLOYMENT_JSON", "vars.PERFORMANCE_CONNECTION_BUDGET_JSON",
                        "secrets.PERFORMANCE_RUNTIME_ACCESS_KEY", "secrets.PERFORMANCE_WORKER_GATEWAY_KEY_ID",
                        "secrets.PERFORMANCE_WORKER_GATEWAY_SECRET", "vars.INSTALLED_RELEASE_TAG",
                        "vars.INSTALLED_RELEASE_COMMIT", "vars.PERFORMANCE_KIT_RELEASE_TAG",
                        "vars.PERFORMANCE_KIT_RELEASE_COMMIT", "github.token"):
            self.assertIn(binding, run)
            self.assertNotIn(binding, finalize)
        self.assertIn("--action stop", run_body(finalize))
        guard = re.search(r"^        if: (.+)$", finalize, re.MULTILINE).group(1)
        for outcome in ("success", "failure", "cancelled"):
            self.assertTrue(expression(guard, outcome=outcome))
            self.assertFalse(expression(guard, outcome=outcome, observation_plan="calendar.json", admitted="false"))
            self.assertTrue(expression(guard, outcome=outcome, observation_plan="calendar.json", admitted="true"))
        self.assertFalse(expression(guard, outcome="skipped"))
        self.assertFalse(expression(guard, outcome="skipped", observation_plan="calendar.json", admitted="true"))

    def test_first_attempt_contract_preflight_precedes_run_without_secret_or_ssh_inputs(self):
        preflight = step(self.external, "Validate consumer RPC contract")
        run = step(self.external, "Run the published Performance consumer")
        self.assertLess(self.external.index(preflight), self.external.index(run))
        guard = re.search(r"^        if: (.+)$", preflight, re.MULTILINE).group(1)
        self.assertTrue(expression(guard, attempt=1))
        self.assertFalse(expression(guard, attempt=2))
        self.assertIn("--action preflight", run_body(preflight))
        self.assertIn('--observation-plan "$OBSERVATION_PLAN"', run_body(preflight))
        self.assertIn('"${observation[@]}"', run_body(preflight))
        self.assertIn("OBSERVATION_SCHEDULE: ${{ github.event.schedule }}", preflight)
        for binding in ("vars.PERFORMANCE_DEPLOYMENT_JSON", "vars.PERFORMANCE_KIT_RELEASE_TAG",
                        "vars.PERFORMANCE_KIT_RELEASE_COMMIT", "vars.INSTALLED_RELEASE_TAG", "vars.INSTALLED_RELEASE_COMMIT"):
            self.assertIn(binding, preflight)
        self.assertNotIn("secrets.", preflight)
        self.assertNotIn("SSH_", preflight)
        self.assertNotIn("GH_TOKEN", preflight)
        # Default success admission skips Run after a failed preflight. The existing
        # finalizer then observes a skipped Run, so it never opens SSH either.
        self.assertNotIn("        if:", run)
        finalize_guard = re.search(r"^        if: (.+)$", step(self.external, "Finalize the same external run"), re.MULTILINE).group(1)
        self.assertFalse(expression(finalize_guard, outcome="skipped"))

    def test_external_artifacts_are_an_explicit_safe_allowlist_outside_source_merge(self):
        upload = step(self.external, "Upload safe external evidence")
        self.assertIn("if: always()", upload)
        self.assertIn("name: external-performance-${{ github.run_attempt }}", upload)
        self.assertIn("retention-days: ${{ inputs.artifact_retention_days }}", upload)
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
            for plan in ("", ".github/external-performance-observation.json", "plan $(touch injected).json"):
                with self.subTest(plan=plan):
                    env = {**os.environ, "PATH": str(temporary) + os.pathsep + os.environ["PATH"],
                           "ARGV_CAPTURE": str(capture), "GITHUB_REPOSITORY": "owner/repo", "WORKERS_PER_GROUP": "5000",
                           "REPETITIONS": "1", "OBSERVATION_PLAN": plan}
                    result = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command],
                                            cwd=temporary, env=env, text=True, capture_output=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    expected = [".github/scripts/run_external_performance.py", "--repository", "owner/repo",
                                "--workers-per-group", "5000", "--repetitions", "1", "--output-dir", "build/external-performance"]
                    if plan:
                        expected += ["--observation-plan", plan]
                    self.assertEqual(expected, json.loads(capture.read_text()))
                    self.assertFalse((temporary / "injected").exists())


if __name__ == "__main__":
    unittest.main()
