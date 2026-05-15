package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

import java.util.List;
import java.util.Map;

/**
 * Write-only assignment diagnostics contract consumed by the engine mainline.
 *
 * <p>Matching and dispatch should only append bounded diagnostic records.
 * Report generation and historical inspection stay behind the concrete
 * diagnostic service rather than leaking into hot-path orchestration seams.
 */
public interface AssignmentDiagnosticRecorder {

    AssignmentRecord recordWorkerAssignment(Task task,
                                            WorkerSchedulingCandidate candidate,
                                            AssignmentResult result,
                                            String reason,
                                            List<RuleEvaluationDetail> ruleEvaluations,
                                            Map<String, Object> contextSnapshot,
                                            boolean workerLocked);

    AssignmentRecord recordMessageAssignment(Task task,
                                             Worker worker,
                                             WorkerContext workerContext,
                                             String messageId,
                                             String batchId,
                                             AssignmentResult result,
                                             String reason,
                                             boolean workerLocked);
}
