package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.monkey.snapshot.TaskSnapshot;
import com.xa.mass.engine.monkey.snapshot.WorkerSchedulingSnapshot;
import com.xa.mass.engine.monkey.snapshot.WorkerSnapshot;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stores assignment-attempt diagnostics for later audit and report generation.
 */
public class AssignmentRecordService implements AssignmentDiagnosticRecorder, AssignmentDiagnosticView {

    private static final Logger log = LoggerFactory.getLogger(AssignmentRecordService.class);

    private final Map<String, AssignmentRecord> records = new ConcurrentHashMap<>();

    /**
     * Records a task-level worker-runtime selection outcome without exposing candidate metadata.
     */
    @Override
    public AssignmentRecord recordWorkerSelectionOutcome(Task task,
                                                         WorkerSelectionResult selectionResult,
                                                         AssignmentResult result,
                                                         String reason,
                                                         Map<String, Object> contextSnapshot) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.WORKER_SELECTION);
        record.setTaskId(task.getTid());
        record.setBatchId("selection-" + System.currentTimeMillis());
        record.setResult(result);
        record.setReason(reason);
        record.setRuleEvaluations(List.of());
        record.setRuleEvaluationCount(0);
        record.setRuleEvaluationTotalTimeMs(0L);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (selectionResult != null) {
            snapshot.put("requestedCount", selectionResult.requestedCount());
            snapshot.put("selectedCount", selectionResult.selectedCount());
            snapshot.put("rejectedCount", selectionResult.rejectedCount());
            snapshot.put("rejectedCountByReason", selectionResult.rejectedCountByReason());
        }
        if (contextSnapshot != null && !contextSnapshot.isEmpty()) {
            snapshot.putAll(contextSnapshot);
        }
        record.setContextSnapshot(Map.copyOf(snapshot));
        record.setTaskSnapshot(createTaskSnapshot(task));

        records.put(record.getRecordId(), record);
        logAssignmentRecord(record);
        return record;
    }

    /**
     * Records a worker-level assignment attempt.
     */
    @Override
    public AssignmentRecord recordWorkerSelection(Task task,
                                                  SelectedWorkerHandle selectedWorker,
                                                  AssignmentResult result,
                                                  String reason,
                                                  Map<String, Object> contextSnapshot) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.WORKER_ASSIGN);
        record.setTaskId(task.getTid());
        record.setWorkerId(selectedWorker.workerId());
        record.setBatchId("batch-" + System.currentTimeMillis());
        record.setResult(result);
        record.setReason(reason);
        record.setRuleEvaluations(List.of());
        record.setRuleEvaluationCount(0);
        record.setRuleEvaluationTotalTimeMs(0L);
        record.setContextSnapshot(contextSnapshot);

        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setWorkerSnapshot(createWorkerSnapshot(selectedWorker));
        record.setWorkerSchedulingSnapshot(createWorkerSchedulingSnapshot(selectedWorker));

        records.put(record.getRecordId(), record);
        logAssignmentRecord(record);
        return record;
    }

    /**
     * Records a message-level assignment attempt.
     */
    @Override
    public AssignmentRecord recordMessageAssignment(Task task, SelectedWorkerHandle selectedWorker,
                                                    String messageId, String batchId,
                                                    AssignmentResult result, String reason) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.MSG_ASSIGN);
        record.setTaskId(task.getTid());
        record.setWorkerId(selectedWorker.workerId());
        record.setMessageId(messageId);
        record.setBatchId(batchId);
        record.setResult(result);
        record.setReason(reason);

        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setWorkerSnapshot(createWorkerSnapshot(selectedWorker));
        record.setWorkerSchedulingSnapshot(createWorkerSchedulingSnapshot(selectedWorker));

        records.put(record.getRecordId(), record);
        logAssignmentRecord(record);
        return record;
    }

    private void logAssignmentRecord(AssignmentRecord record) {
        StringBuilder logMsg = new StringBuilder();
        logMsg.append("[Assignment] ");
        logMsg.append("type=").append(record.getType().name()).append(", ");
        logMsg.append("Task=").append(record.getTaskId()).append(", ");
        logMsg.append("Worker=").append(record.getWorkerId()).append(", ");

        if (record.getMessageId() != null) {
            logMsg.append("Message=").append(record.getMessageId()).append(", ");
        }

        logMsg.append("Batch=").append(record.getBatchId()).append(", ");
        logMsg.append("Result=").append(record.getResult().name()).append(", ");
        logMsg.append("Reason=").append(record.getReason());

        if (record.getRuleEvaluations() != null && !record.getRuleEvaluations().isEmpty()) {
            logMsg.append(", Rules=[");
            String ruleDetails = record.getRuleEvaluations().stream()
                    .map(r -> r.getRuleId() + ":" + (r.isPassed() ? "PASS" : "FAIL"))
                    .collect(Collectors.joining(", "));
            logMsg.append(ruleDetails).append("]");
            logMsg.append(", RuleEvalCount=").append(record.getRuleEvaluationCount());
            logMsg.append(", RuleEvalTimeMs=").append(record.getRuleEvaluationTotalTimeMs());
        }

        log.info(logMsg.toString());
    }

    private TaskSnapshot createTaskSnapshot(Task task) {
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setTaskId(task.getTid());
        snapshot.setTaskName(task.getTaskName());
        snapshot.setProject(task.getProject());
        snapshot.setRoutingCode(TaskSharedConfig.routingCode(task));
        snapshot.setTaskStatus(task.getStatus().name());
        snapshot.setTaskTargetNumber(task.getTaskTargetNumber());
        snapshot.setTaskEligibleNumber(task.getTaskEligibleNumber());
        snapshot.setTaskSuccessNumber(task.getTaskSuccessNumber());
        snapshot.setTaskNonSuccessNumber(task.getTaskNonSuccessNumber());
        snapshot.setMinRequiredWorkerCount(task.getMinRequiredWorkerCount());
        snapshot.setPeakAssignedWorkerCount(task.getPeakAssignedWorkerCount());
        snapshot.setBatchSize(task.getExecutionSpec().getBatchSize());
        snapshot.setCreateTime(task.getCreateTime());
        snapshot.setUpdateTime(task.getUpdateTime());
        return snapshot;
    }

    private WorkerSnapshot createWorkerSnapshot(SelectedWorkerHandle selectedWorker) {
        WorkerSnapshot snapshot = new WorkerSnapshot();
        snapshot.setWorkerId(selectedWorker.workerId());
        snapshot.setWorkerGroupId(selectedWorker.workerGroupId());
        snapshot.setWorkerAvailable(true);
        snapshot.setWorkerLocked(selectedWorker.exclusiveWorkerLock());
        return snapshot;
    }

    private WorkerSchedulingSnapshot createWorkerSchedulingSnapshot(SelectedWorkerHandle selectedWorker) {
        WorkerSchedulingSnapshot snapshot = new WorkerSchedulingSnapshot();
        snapshot.setWorkerId(selectedWorker.workerId());
        snapshot.setWorkerGroupId(selectedWorker.workerGroupId());
        snapshot.setWorkerLocked(selectedWorker.exclusiveWorkerLock());
        return snapshot;
    }

    public List<AssignmentRecord> getRecordsByTaskId(String taskId) {
        return records.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .collect(Collectors.toList());
    }

    public List<AssignmentRecord> getRecordsByWorkerId(String workerId) {
        return records.values().stream()
                .filter(r -> workerId.equals(r.getWorkerId()))
                .collect(Collectors.toList());
    }

    public List<AssignmentRecord> getSuccessfulRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    public List<AssignmentRecord> getFailedRecords() {
        return records.values().stream()
                .filter(r -> !AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    public List<AssignmentRecord> getRuleNotMatchRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.RULE_NOT_MATCH.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    public List<AssignmentRecord> getConflictRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.CONFLICT.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    public Map<String, Object> generateAssignmentReport() {
        Map<String, Object> report = new HashMap<>();

        long totalRecords = records.size();
        long successCount = getSuccessfulRecords().size();
        long failedCount = getFailedRecords().size();
        long ruleNotMatchCount = getRuleNotMatchRecords().size();
        long conflictCount = getConflictRecords().size();

        report.put("totalRecords", totalRecords);
        report.put("successCount", successCount);
        report.put("failedCount", failedCount);
        report.put("ruleNotMatchCount", ruleNotMatchCount);
        report.put("conflictCount", conflictCount);
        report.put("successRate", totalRecords > 0 ? (double) successCount / totalRecords : 0.0);

        Map<String, Long> taskStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getTaskId, Collectors.counting()));
        report.put("taskStats", taskStats);

        Map<String, Long> workerStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getWorkerId, Collectors.counting()));
        report.put("workerStats", workerStats);

        return report;
    }

    public List<Map<String, Object>> detectConflicts() {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        Map<String, List<AssignmentRecord>> workerRecords = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getWorkerId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : workerRecords.entrySet()) {
            String workerId = entry.getKey();
            List<AssignmentRecord> workerRecordList = entry.getValue();

            workerRecordList.sort(Comparator.comparing(AssignmentRecord::getAssignTime));

            for (int i = 0; i < workerRecordList.size() - 1; i++) {
                AssignmentRecord current = workerRecordList.get(i);
                AssignmentRecord next = workerRecordList.get(i + 1);

                if (AssignmentResult.SUCCESS.equals(current.getResult())
                        && AssignmentResult.SUCCESS.equals(next.getResult())) {
                    long timeDiff = java.time.Duration.between(
                            current.getAssignTime(), next.getAssignTime()).toMinutes();
                    if (timeDiff < 5) {
                        Map<String, Object> conflict = new HashMap<>();
                        conflict.put("workerId", workerId);
                        conflict.put("conflictType", "TIME_OVERLAP");
                        conflict.put("firstRecord", current);
                        conflict.put("secondRecord", next);
                        conflict.put("timeDiffMinutes", timeDiff);
                        conflicts.add(conflict);
                    }
                }
            }
        }

        return conflicts;
    }
}
