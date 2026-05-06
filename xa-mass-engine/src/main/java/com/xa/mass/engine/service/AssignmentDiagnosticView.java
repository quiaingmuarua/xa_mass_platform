package com.xa.mass.engine.service;

import com.xa.mass.engine.model.AssignmentRecord;

import java.util.List;
import java.util.Map;

/**
 * Read-only assignment diagnostics view used by validation/report helpers.
 *
 * <p>This keeps diagnostic readers decoupled from the concrete in-memory
 * recorder implementation while leaving hot-path engine code on the narrower
 * write-only recorder contract.
 */
public interface AssignmentDiagnosticView {

    List<AssignmentRecord> getRecordsByTaskId(String taskId);

    List<AssignmentRecord> getRecordsByWorkerId(String workerId);

    List<AssignmentRecord> getSuccessfulRecords();

    List<AssignmentRecord> getFailedRecords();

    List<AssignmentRecord> getRuleNotMatchRecords();

    List<AssignmentRecord> getConflictRecords();

    Map<String, Object> generateAssignmentReport();

    List<Map<String, Object>> detectConflicts();
}
