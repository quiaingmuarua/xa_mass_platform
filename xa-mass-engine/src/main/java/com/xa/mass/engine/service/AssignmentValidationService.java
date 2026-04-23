package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.RuleEvaluationDetail;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates assignment diagnostics for consistency, exclusion reasons, and conflicts.
 */
public class AssignmentValidationService {

    private final AssignmentRecordService recordService;

    public AssignmentValidationService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    /**
     * Forward validation: every successful assignment should have all rules passed.
     */
    public ValidationResult validateSuccessfulAssignments() {
        ValidationResult result = new ValidationResult();
        List<AssignmentRecord> successfulRecords = recordService.getSuccessfulRecords();

        for (AssignmentRecord record : successfulRecords) {
            if (record.getRuleEvaluations() == null) {
                continue;
            }

            boolean allRulesPassed = record.getRuleEvaluations().stream()
                    .allMatch(RuleEvaluationDetail::isPassed);

            if (!allRulesPassed) {
                result.addError("Assignment record " + record.getRecordId()
                        + " is marked successful but contains failed rule evaluations");
            }
        }

        result.setTotalChecked(successfulRecords.size());
        result.setValidatedSuccessfully(result.getErrors().isEmpty());
        return result;
    }

    /**
     * Reverse validation: unassigned workers should have a valid exclusion reason.
     */
    public ValidationResult validateUnassignedWorkers(Set<String> allWorkerIds, Set<String> assignedWorkerIds) {
        ValidationResult result = new ValidationResult();
        Set<String> unassignedWorkerIds = new HashSet<>(allWorkerIds);
        unassignedWorkerIds.removeAll(assignedWorkerIds);

        for (String workerId : unassignedWorkerIds) {
            List<AssignmentRecord> workerRecords = recordService.getRecordsByWorkerId(workerId);

            if (workerRecords.isEmpty()) {
                result.addWarning("Worker " + workerId + " has no assignment records");
                continue;
            }

            boolean hasSuccessfulRecord = workerRecords.stream()
                    .anyMatch(r -> AssignmentResult.SUCCESS.equals(r.getResult()));

            if (hasSuccessfulRecord) {
                result.addError("Worker " + workerId
                        + " has a successful assignment record but is missing from the assigned-worker set");
                continue;
            }

            boolean hasValidReason = workerRecords.stream()
                    .anyMatch(r -> {
                        AssignmentResult res = r.getResult();
                        return AssignmentResult.RULE_NOT_MATCH.equals(res)
                                || AssignmentResult.CONFLICT.equals(res)
                                || AssignmentResult.RESOURCE_UNAVAILABLE.equals(res);
                    });

            if (!hasValidReason) {
                result.addWarning("Worker " + workerId
                        + " was excluded without a recognized conflict/resource/rule reason");
            }
        }

        result.setTotalChecked(unassignedWorkerIds.size());
        result.setValidatedSuccessfully(result.getErrors().isEmpty());
        return result;
    }

    /**
     * Detects worker reuse conflicts and duplicate message assignment conflicts.
     */
    public ConflictDetectionResult detectConflicts() {
        ConflictDetectionResult result = new ConflictDetectionResult();

        Map<String, List<AssignmentRecord>> workerRecords = recordService.getSuccessfulRecords().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getWorkerId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : workerRecords.entrySet()) {
            String workerId = entry.getKey();
            List<AssignmentRecord> records = entry.getValue();

            if (records.size() <= 1) {
                continue;
            }

            records.sort(Comparator.comparing(AssignmentRecord::getAssignTime));

            for (int i = 0; i < records.size() - 1; i++) {
                AssignmentRecord current = records.get(i);
                AssignmentRecord next = records.get(i + 1);

                long timeDiff = java.time.Duration.between(
                        current.getAssignTime(), next.getAssignTime()).toMinutes();

                if (timeDiff < 5) {
                    ConflictInfo conflict = new ConflictInfo();
                    conflict.setWorkerId(workerId);
                    conflict.setConflictType("TIME_OVERLAP");
                    conflict.setFirstRecord(current);
                    conflict.setSecondRecord(next);
                    conflict.setTimeDiffMinutes(timeDiff);
                    conflict.setDescription("Worker was assigned again within a suspiciously short interval");
                    result.addConflict(conflict);
                }
            }
        }

        Map<String, List<AssignmentRecord>> messageRecords = recordService.getSuccessfulRecords().stream()
                .filter(r -> AssignmentType.MSG_ASSIGN.equals(r.getType()))
                .collect(Collectors.groupingBy(AssignmentRecord::getMessageId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : messageRecords.entrySet()) {
            String messageId = entry.getKey();
            List<AssignmentRecord> records = entry.getValue();

            if (records.size() <= 1) {
                continue;
            }

            ConflictInfo conflict = new ConflictInfo();
            conflict.setMessageId(messageId);
            conflict.setConflictType("DUPLICATE_MESSAGE");
            conflict.setFirstRecord(records.get(0));
            conflict.setSecondRecord(records.get(1));
            conflict.setDescription("The same message was assigned more than once");
            result.addConflict(conflict);
        }

        return result;
    }

    /**
     * Builds a summary validation report across success checks, exclusion checks, and conflicts.
     */
    public Map<String, Object> generateValidationReport(Set<String> allWorkerIds, Set<String> assignedWorkerIds) {
        Map<String, Object> report = new HashMap<>();

        ValidationResult positiveValidation = validateSuccessfulAssignments();
        report.put("positiveValidation", positiveValidation);

        ValidationResult negativeValidation = validateUnassignedWorkers(allWorkerIds, assignedWorkerIds);
        report.put("negativeValidation", negativeValidation);

        ConflictDetectionResult conflictDetection = detectConflicts();
        report.put("conflictDetection", conflictDetection);

        report.put("totalWorkers", allWorkerIds.size());
        report.put("assignedWorkers", assignedWorkerIds.size());
        report.put("unassignedWorkers", allWorkerIds.size() - assignedWorkerIds.size());
        report.put("assignmentRate", (double) assignedWorkerIds.size() / allWorkerIds.size());

        return report;
    }

    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int totalChecked;
        private boolean validatedSuccessfully;

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public int getTotalChecked() {
            return totalChecked;
        }

        public void setTotalChecked(int totalChecked) {
            this.totalChecked = totalChecked;
        }

        public boolean isValidatedSuccessfully() {
            return validatedSuccessfully;
        }

        public void setValidatedSuccessfully(boolean validatedSuccessfully) {
            this.validatedSuccessfully = validatedSuccessfully;
        }
    }

    public static class ConflictDetectionResult {
        private final List<ConflictInfo> conflicts = new ArrayList<>();

        public void addConflict(ConflictInfo conflict) {
            conflicts.add(conflict);
        }

        public List<ConflictInfo> getConflicts() {
            return conflicts;
        }

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    public static class ConflictInfo {
        private String workerId;
        private String messageId;
        private String conflictType;
        private AssignmentRecord firstRecord;
        private AssignmentRecord secondRecord;
        private long timeDiffMinutes;
        private String description;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getConflictType() {
            return conflictType;
        }

        public void setConflictType(String conflictType) {
            this.conflictType = conflictType;
        }

        public AssignmentRecord getFirstRecord() {
            return firstRecord;
        }

        public void setFirstRecord(AssignmentRecord firstRecord) {
            this.firstRecord = firstRecord;
        }

        public AssignmentRecord getSecondRecord() {
            return secondRecord;
        }

        public void setSecondRecord(AssignmentRecord secondRecord) {
            this.secondRecord = secondRecord;
        }

        public long getTimeDiffMinutes() {
            return timeDiffMinutes;
        }

        public void setTimeDiffMinutes(long timeDiffMinutes) {
            this.timeDiffMinutes = timeDiffMinutes;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
