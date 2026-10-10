import copy
import unittest
from evaluate_refill_experiment import evaluate


def cases():
    result = {}
    for n in range(1, 4):
        for variant in ("A", "B"):
            result[f"{n}-{variant}"] = {
                "passed": True, "established": 5400, "httpErrors": {}, "generatorRejected": 0,
                "comparison": {"falseSuccesses": 0},
                "initialResponses": {"total": 5400, "notObservedRate": .1 if variant == "A" else .05},
                "clientLatencySamplesMillis": {"acquisition": [1] * 5400, "query": [1] * 5400},
                "requestLatencyMillis": {"p95": 100, "p99": 200}, "queryLatencyMillis": {"p95": 5, "p99": 10},
                "metrics": {"projection": {"matching": {"sms-reception": {"checkedCoordinates": 1000}}}},
                "resourcePeaks": {p: {"cpuSeconds": 100, "rssBytes": 1000, "threads": 10} for p in ("server", "host")},
                "artifacts": {"server": {"sha256": variant}, "hostClasspath": ["same"], "frontendSha256": "same"},
                "launcherSha256": "same"}
    return result


class RefillExperimentTest(unittest.TestCase):
    def test_user_wait_improvement_can_pass_without_changing_p95(self):
        self.assertEqual("retain", evaluate(cases())["performanceDecision"])

    def test_one_good_pair_or_resource_regression_cannot_pass(self):
        data = cases()
        for n in (2, 3): data[f"{n}-B"]["initialResponses"]["notObservedRate"] = .09
        self.assertEqual("withdraw", evaluate(data)["performanceDecision"])
        data = cases()
        for n in range(1, 4): data[f"{n}-B"]["resourcePeaks"]["server"]["cpuSeconds"] = 121
        self.assertEqual("withdraw", evaluate(data)["performanceDecision"])

    def test_incomplete_client_samples_and_changed_supporting_artifacts_fail(self):
        for mutate in (lambda row: row["clientLatencySamplesMillis"]["acquisition"].pop(),
                       lambda row: row["artifacts"].update(frontendSha256="different")):
            data = copy.deepcopy(cases()); mutate(data["1-B"])
            self.assertEqual("withdraw", evaluate(data)["performanceDecision"])


if __name__ == "__main__":
    unittest.main()
