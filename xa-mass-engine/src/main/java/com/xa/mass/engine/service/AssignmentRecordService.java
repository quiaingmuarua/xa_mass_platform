package com.xa.mass.engine.service;

import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.monkey.snapshot.DeviceSnapshot;
import com.xa.mass.engine.monkey.snapshot.TaskSnapshot;
import com.xa.mass.engine.monkey.snapshot.TokenSnapshot;
import com.xa.mass.eventbus.enums.assignment.AssignmentResult;
import com.xa.mass.eventbus.enums.assignment.AssignmentType;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 分配记录服务
 * 负责记录和管理所有分配尝试，支持归因分析和验证
 */
public class AssignmentRecordService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentRecordService.class);

    // 内存存储，实际项目中可替换为数据库
    private final Map<String, AssignmentRecord> records = new ConcurrentHashMap<>();

    /**
     * 记录设备分配尝试
     */
    public AssignmentRecord recordDeviceAssignment(Task task, Device device, Token token,
                                                   AssignmentResult result, String reason,
                                                   List<RuleEvaluationDetail> ruleEvaluations,
                                                   Map<String, Object> contextSnapshot) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.DEVICE_ASSIGN);
        record.setTaskId(task.getTid());
        record.setDeviceId(device.getDeviceId());
        record.setBatchId("batch-" + System.currentTimeMillis());
        record.setResult(result);
        record.setReason(reason);
        record.setRuleEvaluations(ruleEvaluations);
        record.setContextSnapshot(contextSnapshot);

        // 创建快照
        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setDeviceSnapshot(createDeviceSnapshot(device));
        if (token != null) {
            record.setTokenSnapshot(createTokenSnapshot(token));
        }

        records.put(record.getRecordId(), record);

        // 输出归因日志
        logAssignmentRecord(record);

        return record;
    }

    /**
     * 记录消息分配尝试
     */
    public AssignmentRecord recordMessageAssignment(Task task, Device device, Token token,
                                                    String messageId, String batchId,
                                                    AssignmentResult result, String reason) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.MSG_ASSIGN);
        record.setTaskId(task.getTid());
        record.setDeviceId(device.getDeviceId());
        record.setMessageId(messageId);
        record.setBatchId(batchId);
        record.setResult(result);
        record.setReason(reason);

        // 创建快照
        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setDeviceSnapshot(createDeviceSnapshot(device));
        if (token != null) {
            record.setTokenSnapshot(createTokenSnapshot(token));
        }

        records.put(record.getRecordId(), record);

        // 输出归因日志
        logAssignmentRecord(record);

        return record;
    }

    /**
     * 输出归因日志
     */
    private void logAssignmentRecord(AssignmentRecord record) {
        StringBuilder logMsg = new StringBuilder();
        logMsg.append("[Assignment] ");
        logMsg.append("Type=").append(record.getType().getDescription()).append(", ");
        logMsg.append("Task=").append(record.getTaskId()).append(", ");
        logMsg.append("Device=").append(record.getDeviceId()).append(", ");

        if (record.getMessageId() != null) {
            logMsg.append("Message=").append(record.getMessageId()).append(", ");
        }

        logMsg.append("Batch=").append(record.getBatchId()).append(", ");
        logMsg.append("Result=").append(record.getResult().getDescription()).append(", ");
        logMsg.append("Reason=").append(record.getReason());

        if (record.getRuleEvaluations() != null && !record.getRuleEvaluations().isEmpty()) {
            logMsg.append(", Rules=[");
            String ruleDetails = record.getRuleEvaluations().stream()
                    .map(r -> r.getRuleId() + ":" + (r.isPassed() ? "PASS" : "FAIL"))
                    .collect(Collectors.joining(", "));
            logMsg.append(ruleDetails).append("]");
        }

        log.info(logMsg.toString());
    }

    /**
     * 创建任务快照
     */
    private TaskSnapshot createTaskSnapshot(Task task) {
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setTaskId(task.getTid());
        snapshot.setTaskName(task.getTaskName());
        snapshot.setProject(task.getProjectCode());
        snapshot.setTaskCountry(task.getTaskCountry());
        snapshot.setTaskStatus(task.getStatus().name());
        snapshot.setTaskInitNumber(task.getTaskInitNumber());
        snapshot.setTaskValidNumber(task.getTaskValidNumber());
        snapshot.setTaskExecutedNumber(task.getTaskExecutedNumber());
        snapshot.setTaskUnExecutedNumber(task.getTaskUnExecutedNumber());
        snapshot.setRunTaskMinDeviceCnt(task.getRunTaskMinDeviceCnt());
        snapshot.setScheduleDeviceCnt(task.getScheduleDeviceCnt());
        snapshot.setBatchSize(task.getBatchSize());
        snapshot.setCreateTime(task.getCreateTime());
        snapshot.setUpdateTime(task.getUpdateTime());
        return snapshot;
    }

    /**
     * 创建设备快照
     */
    private DeviceSnapshot createDeviceSnapshot(Device device) {
        DeviceSnapshot snapshot = new DeviceSnapshot();
        snapshot.setDeviceId(device.getDeviceId());
        snapshot.setDeviceStatus(device.getStatus().name());
        snapshot.setAgentVersion(device.getAgentVersion());
        snapshot.setLastHeartbeat(device.getLastHeartbeat());
        snapshot.setSupportedProjects(device.getSupportedProjects());
        snapshot.setGroupId(device.getGroupId());
        snapshot.setLockExpireTime(device.getLockExpireTime());
        snapshot.setOnlineStrategy(device.getOnlineStrategy());
        snapshot.setCreateTime(device.getCreateTime());
        snapshot.setUpdateTime(device.getUpdateTime());
        snapshot.setAppCount(device.getSupportedProjects() != null ? device.getSupportedProjects().size() : 0);
        snapshot.setDeviceAvailable(device.isAvailable());
        snapshot.setDeviceLocked(device.isLocked());
        return snapshot;
    }

    /**
     * 创建Token快照
     */
    private TokenSnapshot createTokenSnapshot(Token token) {
        TokenSnapshot snapshot = new TokenSnapshot();
        snapshot.setTokenId(token.getTokenId());
        snapshot.setDeviceId(token.getDeviceId());
        snapshot.setTokenStatus(token.getStatus().name());
        snapshot.setChannel(token.getChannel());
        snapshot.setLastBindTaskId(token.getLastBindTaskId());
        snapshot.setExpireTime(token.getExpireTime());
        snapshot.setCreateTime(token.getCreateTime());
        snapshot.setUpdateTime(token.getUpdateTime());
        snapshot.setLastUsedTime(token.getLastUsedTime());
        snapshot.setTokenAllocatable(token.isAllocatable());
        snapshot.setTokenAvailable(token.isAvailable());
        return snapshot;
    }

    /**
     * 获取任务的所有分配记录
     */
    public List<AssignmentRecord> getRecordsByTaskId(String taskId) {
        return records.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .collect(Collectors.toList());
    }

    /**
     * 获取设备的所有分配记录
     */
    public List<AssignmentRecord> getRecordsByDeviceId(String deviceId) {
        return records.values().stream()
                .filter(r -> deviceId.equals(r.getDeviceId()))
                .collect(Collectors.toList());
    }

    /**
     * 获取成功的分配记录
     */
    public List<AssignmentRecord> getSuccessfulRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 获取失败的分配记录
     */
    public List<AssignmentRecord> getFailedRecords() {
        return records.values().stream()
                .filter(r -> !AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 获取规则不匹配的记录
     */
    public List<AssignmentRecord> getRuleNotMatchRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.RULE_NOT_MATCH.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 获取冲突记录
     */
    public List<AssignmentRecord> getConflictRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.CONFLICT.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 生成分配统计报告
     */
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

        // 按任务分组统计
        Map<String, Long> taskStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getTaskId, Collectors.counting()));
        report.put("taskStats", taskStats);

        // 按设备分组统计
        Map<String, Long> deviceStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getDeviceId, Collectors.counting()));
        report.put("deviceStats", deviceStats);

        return report;
    }

    /**
     * 检测重复/冲突绑定
     */
    public List<Map<String, Object>> detectConflicts() {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        // 检测同一设备在同一时间段的重复分配
        Map<String, List<AssignmentRecord>> deviceRecords = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getDeviceId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : deviceRecords.entrySet()) {
            String deviceId = entry.getKey();
            List<AssignmentRecord> deviceRecordList = entry.getValue();

            // 按时间排序
            deviceRecordList.sort(Comparator.comparing(AssignmentRecord::getAssignTime));

            // 检查是否有时间重叠的成功分配
            for (int i = 0; i < deviceRecordList.size() - 1; i++) {
                AssignmentRecord current = deviceRecordList.get(i);
                AssignmentRecord next = deviceRecordList.get(i + 1);

                if (AssignmentResult.SUCCESS.equals(current.getResult()) &&
                        AssignmentResult.SUCCESS.equals(next.getResult())) {

                    // 检查时间间隔是否过短（可能存在冲突）
                    long timeDiff = java.time.Duration.between(current.getAssignTime(), next.getAssignTime()).toMinutes();
                    if (timeDiff < 5) { // 5分钟内重复分配视为潜在冲突
                        Map<String, Object> conflict = new HashMap<>();
                        conflict.put("deviceId", deviceId);
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