package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.RuleEvaluationDetail;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分配验证服务
 * 支持正向验证、逆向验证和冲突检测
 */
public class AssignmentValidationService {

    private final AssignmentRecordService recordService;

    public AssignmentValidationService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    /**
     * 正向验证：检查每个成功的分配是否都满足规则
     */
    public ValidationResult validateSuccessfulAssignments() {
        ValidationResult result = new ValidationResult();
        List<AssignmentRecord> successfulRecords = recordService.getSuccessfulRecords();

        for (AssignmentRecord record : successfulRecords) {
            if (record.getRuleEvaluations() != null) {
                // 检查是否所有规则都通过
                boolean allRulesPassed = record.getRuleEvaluations().stream()
                        .allMatch(RuleEvaluationDetail::isPassed);

                if (!allRulesPassed) {
                    result.addError("分配记录 " + record.getRecordId() +
                            " 标记为成功但存在规则未通过");
                }
            }
        }

        result.setTotalChecked(successfulRecords.size());
        result.setValidatedSuccessfully(result.getErrors().isEmpty());

        return result;
    }

    /**
     * 逆向验证：检查未分配的 Worker 是否被合理排除
     */
    public ValidationResult validateUnassignedDevices(Set<String> allWorkerIds, Set<String> assignedWorkerIds) {
        ValidationResult result = new ValidationResult();
        Set<String> unassignedWorkerIds = new HashSet<>(allWorkerIds);
        unassignedWorkerIds.removeAll(assignedWorkerIds);

        for (String workerId : unassignedWorkerIds) {
            List<AssignmentRecord> workerRecords = recordService.getRecordsByWorkerId(workerId);

            if (workerRecords.isEmpty()) {
                result.addWarning("Worker " + workerId + " 没有任何分配记录");
                continue;
            }

            // 检查是否有成功的分配记录
            boolean hasSuccessfulRecord = workerRecords.stream()
                    .anyMatch(r -> AssignmentResult.SUCCESS.equals(r.getResult()));

            if (hasSuccessfulRecord) {
                result.addError("Worker " + workerId + " 有成功分配记录但未在已分配列表中");
            } else {
                // 检查失败原因是否合理
                boolean hasValidReason = workerRecords.stream()
                        .anyMatch(r -> {
                            AssignmentResult res = r.getResult();
                            return AssignmentResult.RULE_NOT_MATCH.equals(res) ||
                                    AssignmentResult.CONFLICT.equals(res) ||
                                    AssignmentResult.RESOURCE_UNAVAILABLE.equals(res);
                        });

                if (!hasValidReason) {
                    result.addWarning("Worker " + workerId + " 的失败原因可能不合理");
                }
            }
        }

        result.setTotalChecked(unassignedWorkerIds.size());
        result.setValidatedSuccessfully(result.getErrors().isEmpty());

        return result;
    }

    /**
     * 检测重复/冲突绑定
     */
    public ConflictDetectionResult detectConflicts() {
        ConflictDetectionResult result = new ConflictDetectionResult();

        // 按 Worker 分组检查
        Map<String, List<AssignmentRecord>> workerRecords = recordService.getSuccessfulRecords().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getWorkerId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : workerRecords.entrySet()) {
            String workerId = entry.getKey();
            List<AssignmentRecord> records = entry.getValue();

            if (records.size() > 1) {
                // 按时间排序
                records.sort(Comparator.comparing(AssignmentRecord::getAssignTime));

                // 检查时间重叠
                for (int i = 0; i < records.size() - 1; i++) {
                    AssignmentRecord current = records.get(i);
                    AssignmentRecord next = records.get(i + 1);

                    long timeDiff = java.time.Duration.between(
                            current.getAssignTime(), next.getAssignTime()).toMinutes();

                    if (timeDiff < 5) { // 5分钟内重复分配视为冲突
                        ConflictInfo conflict = new ConflictInfo();
                        conflict.setWorkerId(workerId);
                        conflict.setConflictType("TIME_OVERLAP");
                        conflict.setFirstRecord(current);
                        conflict.setSecondRecord(next);
                        conflict.setTimeDiffMinutes(timeDiff);
                        conflict.setDescription("Worker在短时间内被重复分配");
                        result.addConflict(conflict);
                    }
                }
            }
        }

        // 检查消息分配冲突
        Map<String, List<AssignmentRecord>> messageRecords = recordService.getSuccessfulRecords().stream()
                .filter(r -> AssignmentType.MSG_ASSIGN.equals(r.getType()))
                .collect(Collectors.groupingBy(AssignmentRecord::getMessageId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : messageRecords.entrySet()) {
            String messageId = entry.getKey();
            List<AssignmentRecord> records = entry.getValue();

            if (records.size() > 1) {
                ConflictInfo conflict = new ConflictInfo();
                conflict.setMessageId(messageId);
                conflict.setConflictType("DUPLICATE_MESSAGE");
                conflict.setFirstRecord(records.get(0));
                conflict.setSecondRecord(records.get(1));
                conflict.setDescription("同一消息被分配给多个设备");

                result.addConflict(conflict);
            }
        }

        return result;
    }

    /**
     * 生成验证报告
     */
    public Map<String, Object> generateValidationReport(Set<String> allWorkerIds, Set<String> assignedWorkerIds) {
        Map<String, Object> report = new HashMap<>();

        // 正向验证
        ValidationResult positiveValidation = validateSuccessfulAssignments();
        report.put("positiveValidation", positiveValidation);

        // 逆向验证
        ValidationResult negativeValidation = validateUnassignedDevices(allWorkerIds, assignedWorkerIds);
        report.put("negativeValidation", negativeValidation);

        // 冲突检测
        ConflictDetectionResult conflictDetection = detectConflicts();
        report.put("conflictDetection", conflictDetection);

        // 总体统计
        report.put("totalWorkers", allWorkerIds.size());
        report.put("assignedWorkers", assignedWorkerIds.size());
        report.put("unassignedWorkers", allWorkerIds.size() - assignedWorkerIds.size());
        report.put("assignmentRate", (double) assignedWorkerIds.size() / allWorkerIds.size());

        return report;
    }

    /**
     * 验证结果
     */
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

        // Getters and Setters
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

    /**
     * 冲突检测结果
     */
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

    /**
     * 冲突信息
     */
    public static class ConflictInfo {
        private String workerId;
        private String messageId;
        private String conflictType;
        private AssignmentRecord firstRecord;
        private AssignmentRecord secondRecord;
        private long timeDiffMinutes;
        private String description;

        // Getters and Setters
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