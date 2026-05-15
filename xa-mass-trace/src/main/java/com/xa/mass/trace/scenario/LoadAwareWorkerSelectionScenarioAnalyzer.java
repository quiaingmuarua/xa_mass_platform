package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class LoadAwareWorkerSelectionScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "load-aware-worker-selection";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "Load-aware selection requires accepted worker match evidence");
        List<TraceAssignmentRow> accepted = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .filter(row -> row.candidateRank() != null)
                .sorted(Comparator.comparingInt(TraceAssignmentRow::candidateRank))
                .toList();
        if (accepted.size() < 2) {
            issues.add(new TraceScenarioIssue("INSUFFICIENT_RANKED_ACCEPTED_WORKERS",
                    "Expected at least two ranked accepted workers to prove load-aware ordering"));
            return;
        }
        TraceAssignmentRow first = accepted.getFirst();
        if (first.candidateRank() != 1) {
            issues.add(new TraceScenarioIssue("MISSING_FIRST_RANK",
                    "The first accepted candidate should have candidateRank=1"));
        }
        for (TraceAssignmentRow row : accepted) {
            if (row.workerEstimatedLoadRatio() == null) {
                issues.add(new TraceScenarioIssue("MISSING_WORKER_LOAD_RATIO",
                        "Ranked accepted worker " + row.workerId() + " is missing workerEstimatedLoadRatio"));
            }
        }
        for (int index = 1; index < accepted.size(); index++) {
            TraceAssignmentRow previous = accepted.get(index - 1);
            TraceAssignmentRow current = accepted.get(index);
            if (previous.workerEstimatedLoadRatio() == null || current.workerEstimatedLoadRatio() == null) {
                continue;
            }
            if (previous.workerEstimatedLoadRatio() > current.workerEstimatedLoadRatio()) {
                issues.add(new TraceScenarioIssue("LOAD_ORDER_REGRESSION",
                        "Accepted worker rank order is not nondecreasing by observed load ratio"));
                break;
            }
        }
    }
}
