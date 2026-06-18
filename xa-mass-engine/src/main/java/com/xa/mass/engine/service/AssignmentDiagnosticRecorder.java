package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;

import java.util.Map;

/**
 * Write-only assignment diagnostics contract consumed by the engine mainline.
 *
 * <p>Matching and dispatch should only append bounded diagnostic records.
 * Report generation and historical inspection stay behind the concrete
 * diagnostic service rather than leaking into hot-path orchestration seams.
 */
public interface AssignmentDiagnosticRecorder {

    AssignmentRecord recordWorkerSelectionOutcome(Task task,
                                                  WorkerSelectionResult selectionResult,
                                                  AssignmentResult result,
                                                  String reason,
                                                  Map<String, Object> contextSnapshot);

    AssignmentRecord recordWorkerSelection(Task task,
                                           SelectedWorkerHandle selectedWorker,
                                           AssignmentResult result,
                                           String reason,
                                           Map<String, Object> contextSnapshot);

    AssignmentRecord recordMessageAssignment(Task task,
                                             SelectedWorkerHandle selectedWorker,
                                             String messageId,
                                             String batchId,
                                             AssignmentResult result,
                                             String reason);
}
