"""Finite schedule admission around the existing consumer; no network or workload."""
from argparse import Namespace
from contextlib import ExitStack, contextmanager, nullcontext
from datetime import date, datetime, timedelta, timezone
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("external_observation", ROOT / ".github/scripts/run_external_performance.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
RUN = "100"
HELPER_BYTES = {"orchestrator.py": b"original controller fixture",
                "reporting.py": (ROOT / "integrations/worker-call-performance/reporting.py").read_bytes()}
HELPERS = {"controllerCommit": "c" * 40,
           **{name: hashlib.sha256(content).hexdigest() for name, content in HELPER_BYTES.items()}}
# Synthetic year-boundary window, independent of the repository's live date roster.
START = date(2030, 12, 29)
CURRENT = datetime(2030, 12, 28, 21, 35, tzinfo=timezone.utc)


def cron(slot):
    instant = datetime(slot.year, slot.month, slot.day, 5, 30,
                       tzinfo=timezone(timedelta(hours=8))).astimezone(timezone.utc)
    return f"{instant.minute} {instant.hour} {instant.day} {instant.month} *"

PLAN = {"schemaVersion": 1, "windowId": "external-10k-" + START.strftime("%Y%m%d"), "startDate": START.isoformat(), "days": 7,
        "localTime": "05:30", "utcOffset": "+08:00", "workersPerGroup": 5000, "repetitions": 1}
ENV = {"GITHUB_RUN_ID": RUN, "GITHUB_RUN_ATTEMPT": "1", "GITHUB_EVENT_NAME": "schedule",
       "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": "c" * 40, "OBSERVATION_SCHEDULE": cron(START)}


def observation(slot=START):
    before, after = M.observation_bounds(slot)
    return {"windowId": PLAN["windowId"], "slotDate": slot.isoformat(), "notBeforeUTC": before.isoformat(),
            "notAfterUTC": after.isoformat(), "actualDelaySeconds": 300, "status": "eligible", "reason": None}


def envelope():
    commit = "a" * 40
    return {"deployment": {"consumerId": "public43-performance", "project": "default", "groups": ["a", "b"],
                           "serverDeployment": {"version": "4.4.5", "gitCommit": commit}},
            "serverTag": "v4.4.5", "serverCommit": commit, "tag": "v4.4.5", "commit": commit,
            "workers": 5000, "repetitions": 1, "attempt": 1, "budget": {},
            "accessKey": "xak_private_test_key_12345", "gateway": {"keyId": "gate", "secret": "private-gateway-marker"},
            "kitSha256": "c" * 64, "consumption": {}, "observation": observation(),
            "github": {"controllerCommit": "c" * 40}, "helpers": dict(HELPERS)}


class WindowTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.plan = Path(temporary.name) / "plan.json"
        self.plan.write_text(json.dumps(PLAN))

    def test_each_original_cron_identifies_one_of_seven_local_dates_and_records_delay(self):
        for offset in range(7):
            before, _ = M.observation_bounds(START + timedelta(days=offset))
            with self.subTest(offset=offset), patch.object(M, "observation_now", return_value=before + timedelta(minutes=17)):
                value = M.observation_slot(self.plan, dict(ENV, OBSERVATION_SCHEDULE=cron(START + timedelta(days=offset))), 5000, 1)
                self.assertEqual("eligible", value["status"])
                self.assertEqual((START + timedelta(days=offset)).isoformat(), value["slotDate"])
                self.assertEqual(1020, value["actualDelaySeconds"])

    def test_same_day_delays_are_allowed_but_prior_day_eighth_day_and_reruns_are_not_new_admissions(self):
        before, after = M.observation_bounds(START)
        for instant, expected in ((before - timedelta(seconds=1), "before-observation-slot"),
                                  (after - timedelta(seconds=1), None), (after, "observation-slot-expired")):
            self.assertEqual(expected, M.observation_reason(observation(), instant))
        with patch.object(M, "observation_now", return_value=CURRENT):
            self.assertEqual("not-an-observation-schedule", M.observation_slot(self.plan, dict(ENV, OBSERVATION_SCHEDULE=cron(START + timedelta(days=7))), 5000, 1)["reason"])
            self.assertEqual("rerun-attach-only", M.observation_slot(self.plan, dict(ENV, GITHUB_RUN_ATTEMPT="2"), 5000, 1)["reason"])
            for change in ({"GITHUB_EVENT_NAME": "workflow_dispatch"}, {"GITHUB_REF": "refs/heads/feature"}):
                self.assertEqual("not-original-main-schedule", M.observation_slot(self.plan, dict(ENV, **change), 5000, 1)["reason"])

    def test_remote_slot_bounds_are_revalidated_not_trusted_as_an_arbitrary_time_range(self):
        invalid = dict(observation(), notAfterUTC="2031-02-01T00:00:00+00:00")
        self.assertEqual("invalid-observation", M.observation_reason(invalid, CURRENT))
        self.assertEqual("invalid-observation", M.observation_reason(observation(START + timedelta(days=7)), CURRENT))
        with self.assertRaises(M.Failure):
            M.observation_slot(self.plan, ENV, 1000, 1)


class RemoteAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "runs" / RUN
        self.private = self.base / "private" / RUN
        self.root.mkdir(parents=True)
        # A controller copies immutable helpers before either new Run can be prepared.
        for identifier in (RUN, "101"):
            helper_root = self.base / "runs" / identifier
            helper_root.mkdir(exist_ok=True)
            for name, content in HELPER_BYTES.items():
                (helper_root / name).write_bytes(content)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(M, "paths", side_effect=lambda identifier: (self.base / "runs" / identifier, self.base / "private" / identifier)))
        self.stack.enter_context(patch.object(M, "locked", side_effect=lambda *_a, **_k: nullcontext()))
        self.stack.enter_context(patch.object(M.os, "getuid", return_value=self.root.stat().st_uid, create=True))
        self.stack.enter_context(patch.object(M, "observation_now", return_value=CURRENT))
        self.stack.enter_context(patch.object(M, "current_boot_id", return_value="boot"))
        self.stack.enter_context(patch.object(M, "preflight", return_value={"connections": 10000}))
        self.stack.enter_context(patch.object(M, "extract_kit", return_value=(self.root / "kit", {})))
        self.stack.enter_context(patch.object(M, "arm_prepare_cleanup", side_effect=self.arm))
        self.stack.enter_context(patch.object(M, "stop_prepare_cleanup"))
        self.stack.enter_context(patch.object(M, "unit_status", return_value={"LoadState": "not-found", "ActiveState": "inactive", "SubState": "dead"}))
        self.call = self.stack.enter_context(patch.object(M, "call", return_value=b""))
        self.spawn = self.stack.enter_context(patch.object(M.subprocess, "Popen"))

    def arm(self, identifier, root):
        M.write_json(root / "prepare-lease.json", {"bootId": "boot", "expiresAtMonotonic": M.time.monotonic() + 300, "armConfirmed": True})

    def prior(self, identifier="90", *, stamp="2030-12-28T20:00:00+00:00", receipt=True, clean=True, passed=False, started=True):
        root = self.base / "runs" / identifier
        root.mkdir(parents=True, exist_ok=True)
        M.write_json(root / "state.json", {"preparedAt": stamp})
        if receipt:
            M.write_json(root / "receipt.json", {"status": "passed" if passed else "failed", "privateInputsRemoved": True,
                                                  "finalizationFailures": [], "finishedAt": stamp})
        if started:
            M.write_json(root / "start-request.json", {"requestedAt": stamp})
            (root / "result/evidence").mkdir(parents=True, exist_ok=True)
            M.write_json(root / "result/evidence/lane-summary.json", {"status": "passed" if passed else "failed", "cleanup": {
                "ownedProcessesStopped": True, "privateCredentialCopiesRemoved": True, "errors": [],
                "workerConnections": {"status": "confirmed" if clean else "unconfirmed"}}})
        return root

    def test_latest_is_chronological_not_the_largest_synthetic_run_number(self):
        synthetic = self.prior("999900000000000005", stamp="2030-12-27T11:00:00+00:00", started=False)
        M.write_json(synthetic / "state.json", {"github": {}})
        actual = self.prior("44368693491", stamp="2030-12-27T18:21:04Z")
        self.assertEqual(actual, M.latest_prior_run(self.root))
        self.assertIsNone(M.prior_admission_reason(self.root, scheduled=True))
        self.assertNotEqual(synthetic, M.latest_prior_run(self.root))
        (synthetic / "receipt.json").unlink()
        timestamp = datetime(2030, 12, 27, 10, 55, tzinfo=timezone.utc).timestamp()
        os.utime(synthetic / "state.json", (timestamp, timestamp))
        self.assertEqual(actual, M.latest_prior_run(self.root))

    def test_failed_but_clean_is_allowed_passed_but_unclean_is_missing_and_old_failures_do_not_ban_later_runs(self):
        self.prior("80", stamp="2030-12-27T11:00:00+00:00", clean=False)
        latest = self.prior("90", passed=False)
        self.assertIsNone(M.prior_admission_reason(self.root, scheduled=True))
        M.write_json(latest / "result/evidence/lane-summary.json", {"status": "passed", "cleanup": {}})
        value = M.remote_prepare(RUN, envelope())
        self.assertEqual("prior-cleanup-unconfirmed", value["reason"])
        self.assertFalse(self.private.exists())
        self.call.assert_not_called()
        self.spawn.assert_not_called()
        self.assertTrue((self.root.parent / "observation-windows" / PLAN["windowId"] / f"{START.isoformat()}.json").exists())

    def test_pending_reservation_blocks_both_entrypoints_and_clean_never_started_receipt_is_safe(self):
        prior = self.prior(receipt=False, started=False)
        manual = envelope(); manual.pop("observation")
        with self.assertRaises(M.Failure):
            M.remote_prepare(RUN, manual)
        self.assertEqual("prior-reservation-pending", M.remote_prepare(RUN, envelope())["reason"])
        self.assertFalse(self.private.exists())
        M.write_json(prior / "receipt.json", {"privateInputsRemoved": True, "finalizationFailures": []})
        self.assertIsNone(M.prior_admission_reason(self.root, scheduled=True))

    def test_remaining_active_exited_unit_is_terminal_but_running_is_a_collision(self):
        self.prior()
        self.assertIsNone(M.prior_admission_reason(self.root, scheduled=True))  # Reaped not-found/inactive/dead unit.
        with patch.object(M, "unit_status", return_value={"LoadState": "loaded", "ActiveState": "active", "SubState": "exited"}):
            self.assertIsNone(M.prior_admission_reason(self.root, scheduled=True))
        with patch.object(M, "unit_status", return_value={"LoadState": "loaded", "ActiveState": "active", "SubState": "running"}):
            self.assertEqual("prior-unit-running", M.prior_admission_reason(self.root, scheduled=True))
        with patch.object(M, "unit_status", return_value={"LoadState": "loaded"}):
            self.assertEqual("prior-unit-state-unconfirmed", M.prior_admission_reason(self.root, scheduled=True))

    def test_entire_prepare_holds_global_lock_until_state_is_published_then_reservation_blocks_another_run(self):
        held = []
        @contextmanager
        def locking(path, **kwargs):
            if path.name == "performance.lock": held.append(True)
            try: yield
            finally:
                if path.name == "performance.lock": held.pop()
        original = M.write_json
        def write(path, value, **kwargs):
            if path.name in {"deployment.json", "state.json", "gateway.json"}:
                self.assertTrue(held)
            return original(path, value, **kwargs)
        with patch.object(M, "locked", side_effect=locking), patch.object(M, "write_json", side_effect=write):
            manual = envelope(); manual.pop("observation")
            self.assertEqual("prepared", M.remote_prepare(RUN, manual)["state"])
        self.assertFalse(held)
        self.assertEqual("prior-reservation-pending", M.remote_prepare("101", envelope())["reason"])
        self.assertFalse((self.base / "private/101").exists())

    def test_global_collision_consumes_the_day_without_credentials_or_a_start(self):
        def locking(path, **kwargs):
            if path.name == "performance.lock": raise M.LockBusy("busy")
            return nullcontext()
        with patch.object(M, "locked", side_effect=locking):
            self.assertEqual("consumer-busy", M.remote_prepare(RUN, envelope())["reason"])
        self.assertFalse(self.private.exists())
        self.call.assert_not_called()
        self.spawn.assert_not_called()
        self.assertEqual("observation-slot-already-claimed", M.remote_prepare("101", envelope())["reason"])

    def test_two_racing_day_claims_have_one_winner_and_no_retry_reopens_the_claim(self):
        barrier, results = threading.Barrier(2), []
        def claimant(identifier):
            try:
                barrier.wait(timeout=5)
                M.claim_observation(self.base / "runs" / identifier, identifier, observation())
                results.append("claimed")
            except M.ObservationSkipped:
                results.append("missing")
        threads = [threading.Thread(target=claimant, args=(value,)) for value in ("101", "102")]
        for thread in threads: thread.start()
        for thread in threads: thread.join(timeout=5)
        self.assertCountEqual(["claimed", "missing"], results)

    def test_expired_remote_prepare_does_not_write_credentials(self):
        with patch.object(M, "observation_now", return_value=CURRENT + timedelta(days=1)):
            self.assertEqual("observation-slot-expired", M.remote_prepare(RUN, envelope())["reason"])
        self.assertFalse(self.private.exists())
        self.call.assert_not_called()

    def test_expired_start_and_queued_execute_remove_inputs_without_spawning_load(self):
        for execute in (False, True):
            with self.subTest(execute=execute):
                identifier = "100" if not execute else "101"
                root = self.base / "runs" / identifier
                private = self.base / "private" / identifier
                if execute:
                    # The first no-load Run is finalized, so the second uses the next slot.
                    next_value = envelope(); next_value["observation"] = observation(START + timedelta(days=1))
                    with patch.object(M, "observation_now", return_value=CURRENT + timedelta(days=1)):
                        self.assertEqual("prepared", M.remote_prepare(identifier, next_value)["state"])
                    M.write_json(root / "start-request.json", {"requestedAt": "fixture"})
                else:
                    self.assertEqual("prepared", M.remote_prepare(identifier, envelope())["state"])
                with patch.object(M, "observation_now", return_value=CURRENT + timedelta(days=2)):
                    if execute: self.assertEqual(1, M.remote_execute(identifier))
                    else: self.assertEqual("observation-skipped", M.remote_start(identifier)["state"])
                self.assertFalse((private / "access-key.txt").exists())
                self.assertFalse((private / "gateway.json").exists())
                self.assertTrue(M.read_json(root / "receipt.json")["privateInputsRemoved"])
        self.call.assert_not_called()
        self.spawn.assert_not_called()

    def test_clock_crossing_the_deadline_during_pre_spawn_io_cannot_start_an_eighth_day_load(self):
        self.assertEqual("prepared", M.remote_prepare(RUN, envelope())["state"])
        M.write_json(self.root / "start-request.json", {"requestedAt": "fixture"})
        clock = [CURRENT]
        original = M.write_json
        def write(path, value, **kwargs):
            result = original(path, value, **kwargs)
            if path.name == "observation.json" and value.get("status") == "started":
                clock[0] = CURRENT + timedelta(days=7)
            return result
        with patch.object(M, "observation_now", side_effect=lambda: clock[0]), \
                patch.object(M, "write_json", side_effect=write), patch.object(M.signal, "signal"):
            self.assertEqual(1, M.remote_execute(RUN))
        self.spawn.assert_not_called()
        self.assertFalse((self.private / "access-key.txt").exists())
        self.assertEqual("missing", M.remote_collect(RUN)["summary.json"]["status"])

    def test_a_later_failed_cleanup_receipt_overrides_the_original_no_secret_skip_marker(self):
        M.skipped_preparation(self.root, observation(), "observation-slot-expired")
        M.write_json(self.root / "receipt.json", {"privateInputsRemoved": False, "finalizationFailures": ["PermissionError"]})
        value = M.remote_collect(RUN)
        self.assertFalse(value["orchestration.json"]["cleanupConfirmed"])
        self.assertTrue(value["orchestration.json"]["cleanupRequired"])


class ControllerObservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.plan = self.base / "plan.json"; self.plan.write_text(json.dumps(PLAN))
        self.output = self.base / "public"; self.github_output = self.base / "github-output"
        self.args = Namespace(repository="owner/repo", workers_per_group=5000, repetitions=1, output_dir=self.output,
                              action="run", observation_plan=self.plan)
        source = envelope()
        self.env = dict(ENV, GITHUB_OUTPUT=str(self.github_output), RELEASE_TAG=source["tag"], RELEASE_COMMIT=source["commit"],
                        SERVER_RELEASE_TAG=source["serverTag"], SERVER_RELEASE_COMMIT=source["serverCommit"],
                        DEPLOYMENT_JSON=json.dumps(source["deployment"]), CONNECTION_BUDGET_JSON="{}",
                        RUNTIME_ACCESS_KEY=source["accessKey"], WORKER_GATEWAY_KEY_ID="gate", WORKER_GATEWAY_SECRET="private-gateway-marker")

    def execute(self, fake, current=CURRENT):
        downloader = SimpleNamespace(prepare=Mock(return_value={"release": "release", "releaseId": 1, "tag": "v4.4.5",
                "gitCommit": "a" * 40, "kitSha256": "c" * 64, "manifest": {}}))
        with patch.dict(os.environ, self.env, clear=True), patch.object(M, "observation_now", return_value=current), \
                patch.object(M, "Transport", return_value=fake) as transport, patch.object(M.time, "sleep"), \
                patch.object(M.importlib.util, "spec_from_file_location", return_value=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda _m: None))), \
                patch.object(M.importlib.util, "module_from_spec", return_value=downloader):
            code = M.controller(self.args)
        return code, transport, downloader

    def test_outside_original_day_and_day_eight_have_four_missing_artifacts_without_ssh(self):
        code, transport, downloader = self.execute(None, CURRENT + timedelta(days=7))
        self.assertEqual(0, code); transport.assert_not_called(); downloader.prepare.assert_not_called()
        self.assertEqual(set(M.FILES), {path.name for path in self.output.iterdir()})
        self.assertEqual("missing", M.read_json(self.output / "summary.json")["status"])
        self.assertIn("observation_admitted=false", self.github_output.read_text())

    def fake(self, *, attached=False, skip=False, unknown=False):
        actions = []
        outer = self
        class Transport:
            def copy(self, *args): pass
            def ssh(self, command, data=None, timeout=60):
                if command[1] == "-c":
                    actions.append("bootstrap")
                    return {"root": "/home/xa-mass-performance/runs/100", "helper": attached}
                action = command[3]; actions.append(action)
                if action == "prepare":
                    outer.assertTrue(outer.github_output.read_text().rstrip().endswith("observation_admitted=true"))
                    outer.assertIn("observation", json.loads(data))
                    if unknown: raise M.Failure("transport acknowledgement unknown")
                    if skip: return {"state": "observation-skipped", "reason": "consumer-busy", "cleanupConfirmed": True}
                    return {"state": "prepared"}
                if action == "start": return {"state": "finished"}
                if action == "status": return {"state": "finished"}
                if action == "collect":
                    return {"summary.json": {"status": "passed"}, "summary.md": "# Original summary\n", "consumption.json": {},
                            "orchestration.json": {"state": "finished", "receipt": {"status": "passed", "privateInputsRemoved": True}}}
                raise AssertionError(action)
        return Transport(), actions

    def test_remote_skip_never_starts_and_resets_admitted_after_known_skip(self):
        fake, actions = self.fake(skip=True)
        self.assertEqual(0, self.execute(fake)[0])
        self.assertEqual(["bootstrap", "prepare"], actions)
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=false"))
        public = "".join(path.read_text() for path in self.output.iterdir())
        self.assertNotIn("private-gateway-marker", public)
        self.assertNotIn("xak_private", public)
        self.assertEqual("missing", M.read_json(self.output / "summary.json")["status"])

    def test_unknown_prepare_ack_leaves_admitted_true_for_original_run_finalization(self):
        fake, actions = self.fake(unknown=True)
        with self.assertRaises(M.Failure): self.execute(fake)
        self.assertEqual(["bootstrap", "prepare"], actions)
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=true"))
        self.assertEqual(set(M.FILES), {path.name for path in self.output.iterdir()})
        self.assertTrue(M.read_json(self.output / "orchestration.json")["cleanupRequired"])
        self.assertEqual("unconfirmed", M.read_json(self.output / "orchestration.json")["state"])
        self.assertEqual("unconfirmed", M.read_json(self.output / "consumption.json")["status"])

    def test_bootstrap_failure_has_four_missing_artifacts_without_claiming_admission(self):
        fake, _ = self.fake()
        fake.ssh = Mock(side_effect=M.Failure("transport unavailable"))
        with self.assertRaises(M.Failure): self.execute(fake)
        self.assertEqual(set(M.FILES), {path.name for path in self.output.iterdir()})
        self.assertEqual("missing", M.read_json(self.output / "summary.json")["status"])
        self.assertEqual("not-run", M.read_json(self.output / "consumption.json")["status"])
        self.assertFalse(M.read_json(self.output / "orchestration.json")["cleanupRequired"])
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=false"))

    def test_failed_original_run_query_keeps_finalization_eligible_without_new_admission(self):
        for action, attempt in (("run", "2"), ("stop", "1"), ("collect", "1")):
            with self.subTest(action=action):
                self.args.action = action
                self.env["GITHUB_RUN_ATTEMPT"] = attempt
                fake, _ = self.fake()
                fake.ssh = Mock(side_effect=M.Failure("original transport unavailable"))
                with self.assertRaises(M.Failure): self.execute(fake, CURRENT + timedelta(days=7))
                self.assertEqual(1, fake.ssh.call_count)
                self.assertTrue(M.read_json(self.output / "orchestration.json")["cleanupRequired"])
                self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=true"))

    def test_rerun_can_only_attach_collect_and_never_prepare_or_start(self):
        self.env["GITHUB_RUN_ATTEMPT"] = "2"
        fake, actions = self.fake(attached=True)
        self.assertEqual(0, self.execute(fake, CURRENT + timedelta(days=7))[0])
        self.assertEqual(["bootstrap", "status", "collect"], actions)
        self.assertIn("Observation window:", (self.output / "summary.md").read_text())
        self.assertEqual(PLAN["windowId"], M.read_json(self.output / "orchestration.json")["observation"]["windowId"])
        fake, actions = self.fake(attached=False)
        self.assertEqual(1, self.execute(fake)[0])
        self.assertEqual(["bootstrap"], actions)
        self.assertEqual("passed", M.read_json(self.output / "summary.json")["status"])
        self.assertTrue(M.read_json(self.output / "orchestration.json")["cleanupRequired"])
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=true"))

    def test_missing_helper_on_an_original_run_query_does_not_prove_no_unit_or_no_consumption(self):
        self.env["GITHUB_RUN_ATTEMPT"] = "2"
        fake, actions = self.fake(attached=False)
        self.assertEqual(1, self.execute(fake)[0])
        self.assertEqual(["bootstrap"], actions)
        self.assertEqual("missing", M.read_json(self.output / "summary.json")["status"])
        self.assertEqual("unconfirmed", M.read_json(self.output / "consumption.json")["status"])
        self.assertEqual("unconfirmed", M.read_json(self.output / "orchestration.json")["state"])
        self.assertTrue(M.read_json(self.output / "orchestration.json")["cleanupRequired"])
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=true"))

    def test_stop_ssh_failure_preserves_collected_reports_and_only_updates_orchestration(self):
        M.write_collection(self.output, {"summary.json": {"status": "failed", "cases": [{"status": "saturated", "rate": 2000}]},
                "summary.md": "# Actual case evidence\n", "consumption.json": {"tag": "v4.4.5", "kitSha256": "c" * 64},
                "orchestration.json": {"state": "finished", "receipt": {"status": "failed", "privateInputsRemoved": True}}})
        preserved = {name: (self.output / name).read_bytes() for name in M.FILES if name != "orchestration.json"}
        self.args.action = "stop"
        fake, actions = self.fake(attached=True)
        original_ssh = fake.ssh
        def ssh(command, data=None, timeout=60):
            if command[1] != "-c" and command[3] == "stop":
                actions.append("stop")
                raise M.Failure("original transport unavailable")
            return original_ssh(command, data=data, timeout=timeout)
        fake.ssh = ssh
        with self.assertRaises(M.Failure): self.execute(fake, CURRENT + timedelta(days=7))
        self.assertEqual(["bootstrap", "stop"], actions)
        for name, original in preserved.items():
            self.assertEqual(original, (self.output / name).read_bytes(), name)
        receipt = M.read_json(self.output / "orchestration.json")
        self.assertEqual("failed", receipt["receipt"]["status"])
        self.assertEqual("unconfirmed", receipt["controllerStatus"])
        self.assertTrue(receipt["cleanupRequired"])

    def test_rerun_collects_original_clean_skip_without_waiting_or_claiming_a_second_day(self):
        self.env["GITHUB_RUN_ATTEMPT"] = "2"
        fake, actions = self.fake(attached=True)
        original_ssh = fake.ssh
        def ssh(command, data=None, timeout=60):
            if command[1] != "-c" and command[3] == "status":
                actions.append("status")
                return {"state": "observation-skipped"}
            if command[1] != "-c" and command[3] == "collect":
                actions.append("collect")
                collection = M.missing_observation(RUN, observation(), "consumer-busy")
                collection["orchestration.json"]["cleanupConfirmed"] = True
                return collection
            return original_ssh(command, data=data, timeout=timeout)
        fake.ssh = ssh
        self.assertEqual(0, self.execute(fake, CURRENT + timedelta(days=7))[0])
        self.assertEqual(["bootstrap", "status", "collect"], actions)
        self.assertTrue(self.github_output.read_text().rstrip().endswith("observation_admitted=false"))
        self.assertEqual("missing", M.read_json(self.output / "summary.json")["status"])


if __name__ == "__main__":
    unittest.main()
