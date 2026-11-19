"""Offline fixtures for the current published Harness evidence, without a kit upgrade."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("performance_report_fixture", Path(__file__).with_name("reporting.py"))
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class ReportingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, relative, value):
        path = self.root / "evidence" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def rows(self, relative, rows):
        path = self.write("harness/" + relative, {})
        path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
        return path

    def fixture(self):
        # Fields emitted by LaneCase and CallLoad; no version-specific reader or runner import.
        case = {"status": "invalid", "invalidReasons": ["worker-observation-unavailable"],
                "completedPerSecond": 3.5, "workerSamples": [], "warmup": {"samples": 2},
                "metrics": {"successRate": 0.2, "successfulCallLatencyMillis": {"p50": 2, "p99": 5},
                            "unknownSubmissions": 2, "http429": 1,
                            "acceptedResultsAfterDrain": {"succeeded": 1, "failed": 1, "not_observed": 1}}}
        entry = {"case": "task-any-500", "repetition": 1, "mode": "open", "rate": 500, "path": "task-any"}
        projected = REPORT.lane_case_record(entry, case, None)
        self.write("lane-summary.json", {"status": "passed", "caseCounts": {"invalid": 1}, "cases": [projected],
                                        "warmup": {"case": "task-any-500", "status": "passed"},
                                        "cleanup": {"workerConnections": {"status": "unconfirmed"}},
                                        "resourcePeaks": {"host": {"peakRssBytes": 1024}}})
        self.write("harness/r1/task-any-500/case.json", case)
        samples = [
            {"sentOffsetMillis": -1, "httpStatus": 0, "outcome": "not_sent", "observedResult": "not_observed"},
            {"sentOffsetMillis": 0, "httpStatus": 0, "outcome": "unknown", "observedResult": "not_observed"},
            {"sentOffsetMillis": 1, "httpStatus": 429, "outcome": "unknown", "observedResult": "not_observed"},
            {"sentOffsetMillis": 2, "httpStatus": 200, "outcome": "not_observed", "observedResult": "not_observed"},
            {"sentOffsetMillis": 3, "httpStatus": 200, "outcome": "succeeded", "observedResult": "succeeded"},
            {"sentOffsetMillis": 4, "httpStatus": 200, "outcome": "failed", "observedResult": "failed"}]
        self.rows("r1/task-any-500/samples.jsonl", samples)
        self.rows("r1/task-any-500/warmup-samples.jsonl", samples[:2])
        self.rows("warmup/task-any-500/samples.jsonl", samples[:3])
        self.rows("warmup/task-any-500/warmup-samples.jsonl", samples[:1])
        return case, projected

    def test_known_evidence_preserves_verdict_metrics_and_distinct_unknowns(self):
        case, projected = self.fixture()
        summary, markdown = REPORT.collect_measurement(self.root)
        self.assertEqual("passed", summary["status"])
        self.assertEqual("invalid", summary["cases"][0]["status"])
        self.assertEqual(case["metrics"], summary["cases"][0]["originalMetrics"])
        for key, value in projected.items():
            self.assertEqual(value, summary["cases"][0][key])
        self.assertEqual("unconfirmed", summary["cleanup"]["workerConnections"]["status"])
        evidence = summary["submissionEvidence"]
        self.assertEqual("complete", evidence["status"])
        self.assertEqual(6, evidence["totals"]["samples"])
        self.assertEqual(1, evidence["totals"]["notSent"])
        self.assertEqual(1, evidence["totals"]["sentHttp0Unknown"])
        self.assertEqual(2, evidence["totals"]["outcome:unknown"])
        self.assertEqual(1, evidence["totals"]["http429"])
        self.assertEqual(4, evidence["totals"]["observed:not_observed"])
        self.assertEqual(4, evidence["phases"]["discardedWarmup"]["samples"])
        self.assertEqual(2, evidence["phases"]["innerWarmup"]["samples"])
        self.assertIn("invalid (worker-observation-unavailable)", markdown)
        self.assertIn("Measurement evidence: **complete**", markdown)

    def test_absent_bad_or_empty_evidence_is_explicit_without_zero_fill(self):
        for damage in ("missing", "malformed", "wrong-shape", "empty"):
            with self.subTest(damage=damage):
                self.fixture()
                metrics = self.root / "evidence/harness/r1/task-any-500/case.json"
                samples = self.root / "evidence/harness/r1/task-any-500/samples.jsonl"
                if damage == "missing":
                    metrics.unlink()
                    samples.unlink()
                else:
                    metrics.write_text({"malformed": "{", "wrong-shape": "[]", "empty": '{"metrics":{}}'}[damage])
                    samples.write_text({"malformed": "{\n", "wrong-shape": '{}\n{"sentOffsetMillis":false}\n', "empty": ""}[damage])
                summary, _ = REPORT.collect_measurement(self.root)
                row = summary["cases"][0]
                self.assertEqual("unavailable", row["originalMetricsStatus"])
                self.assertNotIn("originalMetrics", row)
                self.assertEqual("incomplete", summary["submissionEvidence"]["status"])
                self.assertNotIn("samples", summary["submissionEvidence"]["totals"])
                self.assertEqual("incomplete", summary["reportCollection"]["status"])

    def test_partial_sample_corruption_retains_valid_counts_but_never_claims_complete(self):
        self.fixture()
        path = self.root / "evidence/harness/r1/task-any-500/samples.jsonl"
        with path.open("a", encoding="utf-8") as stream:
            stream.write('{"sentOffsetMillis":NaN,"httpStatus":0,"outcome":"unknown"}\n[]\n')
        summary, _ = REPORT.collect_measurement(self.root)
        counts = summary["submissionEvidence"]["totals"]
        self.assertEqual(6, counts["samples"])
        self.assertEqual(2, counts["invalidRows"])
        self.assertEqual("incomplete", summary["submissionEvidence"]["status"])

    def test_missing_warmup_evidence_does_not_get_covered_by_formal_samples(self):
        self.fixture()
        (self.root / "evidence/harness/r1/task-any-500/warmup-samples.jsonl").unlink()
        (self.root / "evidence/harness/warmup/task-any-500/samples.jsonl").unlink()
        summary, _ = REPORT.collect_measurement(self.root)
        evidence = summary["submissionEvidence"]
        self.assertEqual("incomplete", evidence["status"])
        self.assertEqual(6, evidence["totals"]["samples"])
        self.assertEqual({"innerWarmup/r1/task-any-500/warmup-samples.jsonl": "missing-samples",
                          "discardedWarmup/warmup/task-any-500/samples.jsonl": "missing-samples"}, evidence["unavailable"])

    def test_missing_or_malformed_summary_does_not_become_a_pass(self):
        summary, markdown = REPORT.collect_measurement(self.root)
        self.assertEqual("unknown", summary["status"])
        self.assertEqual("unavailable", summary["reportCollection"]["status"])
        self.assertEqual("unavailable", summary["submissionEvidence"]["status"])
        self.assertEqual({}, summary["submissionEvidence"]["totals"])
        path = self.write("lane-summary.json", [])
        summary, _ = REPORT.collect_measurement(self.root)
        self.assertEqual("ValueError", summary["summaryParseFailure"])
        path.write_text('{"status":"passed","cases":[null]}')
        self.assertEqual("unknown", REPORT.collect_measurement(self.root)[0]["status"])

    def test_collector_renders_measurements_without_retained_markdown(self):
        self.fixture()
        self.write("summary.md", "a stale private Markdown snapshot")
        summary, markdown = REPORT.collect_measurement(self.root)
        self.assertNotIn("stale private", markdown)
        self.assertIn("# Performance Lane", markdown)
        self.assertIn("formal totals exclude warmup", markdown)

    def test_missing_worker_observations_are_not_a_zero_sample_measurement(self):
        _, row = self.fixture()
        row.pop("workerSamples")
        projected = REPORT.lane_case_record(row, {"status": "invalid"}, None)
        self.assertIsNone(projected["workerSamples"])


if __name__ == "__main__":
    unittest.main()
