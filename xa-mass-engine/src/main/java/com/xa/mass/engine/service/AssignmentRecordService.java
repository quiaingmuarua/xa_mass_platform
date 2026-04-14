package com.xa.mass.engine.service;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.monkey.snapshot.DeviceSnapshot;
import com.xa.mass.engine.monkey.snapshot.TaskSnapshot;
import com.xa.mass.engine.monkey.snapshot.TokenSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 鍒嗛厤璁板綍鏈嶅姟
 * 璐熻矗璁板綍鍜岀鐞嗘墍鏈夊垎閰嶅皾璇曪紝鏀寔褰掑洜鍒嗘瀽鍜岄獙璇?
 */
public class AssignmentRecordService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentRecordService.class);

    // 鍐呭瓨瀛樺偍锛屽疄闄呴」鐩腑鍙浛鎹负鏁版嵁搴?
    private final Map<String, AssignmentRecord> records = new ConcurrentHashMap<>();

    /**
     * 璁板綍璁惧鍒嗛厤灏濊瘯
     */
    public AssignmentRecord recordDeviceAssignment(Task task, Device device, Token token,
                                                   AssignmentResult result, String reason,
                                                   List<RuleEvaluationDetail> ruleEvaluations,
                                                   Map<String, Object> contextSnapshot,
                                                   boolean deviceLocked) {
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

        // 鍒涘缓蹇収
        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setDeviceSnapshot(createDeviceSnapshot(device, deviceLocked));
        if (token != null) {
            record.setTokenSnapshot(createTokenSnapshot(token));
        }

        records.put(record.getRecordId(), record);

        // 杈撳嚭褰掑洜鏃ュ織
        logAssignmentRecord(record);

        return record;
    }

    /**
     * 璁板綍娑堟伅鍒嗛厤灏濊瘯
     */
    public AssignmentRecord recordMessageAssignment(Task task, Device device, Token token,
                                                    String messageId, String batchId,
                                                    AssignmentResult result, String reason,
                                                    boolean deviceLocked) {
        AssignmentRecord record = new AssignmentRecord();
        record.setRecordId(UUID.randomUUID().toString());
        record.setType(AssignmentType.MSG_ASSIGN);
        record.setTaskId(task.getTid());
        record.setDeviceId(device.getDeviceId());
        record.setMessageId(messageId);
        record.setBatchId(batchId);
        record.setResult(result);
        record.setReason(reason);

        // 鍒涘缓蹇収
        record.setTaskSnapshot(createTaskSnapshot(task));
        record.setDeviceSnapshot(createDeviceSnapshot(device, deviceLocked));
        if (token != null) {
            record.setTokenSnapshot(createTokenSnapshot(token));
        }

        records.put(record.getRecordId(), record);

        // 杈撳嚭褰掑洜鏃ュ織
        logAssignmentRecord(record);

        return record;
    }

    /**
     * 杈撳嚭褰掑洜鏃ュ織
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
     * 鍒涘缓浠诲姟蹇収
     */
    private TaskSnapshot createTaskSnapshot(Task task) {
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setTaskId(task.getTid());
        snapshot.setTaskName(task.getTaskName());
        snapshot.setProject(task.getProjectCode());
        snapshot.setTaskRoutingCountryCode(task.getTaskRoutingCountryCode());
        snapshot.setTaskStatus(task.getStatus().name());
        snapshot.setTaskTargetNumber(task.getTaskTargetNumber());
        snapshot.setTaskEligibleNumber(task.getTaskEligibleNumber());
        snapshot.setTaskSuccessNumber(task.getTaskSuccessNumber());
        snapshot.setTaskNonSuccessNumber(task.getTaskNonSuccessNumber());
        snapshot.setRunTaskMinDeviceCnt(task.getRunTaskMinDeviceCnt());
        snapshot.setScheduleDeviceCnt(task.getScheduleDeviceCnt());
        snapshot.setBatchSize(task.getBatchSize());
        snapshot.setCreateTime(task.getCreateTime());
        snapshot.setUpdateTime(task.getUpdateTime());
        return snapshot;
    }

    /**
     * 鍒涘缓璁惧蹇収
     */
    private DeviceSnapshot createDeviceSnapshot(Device device, boolean deviceLocked) {
        DeviceSnapshot snapshot = new DeviceSnapshot();
        snapshot.setDeviceId(device.getDeviceId());
        snapshot.setDeviceStatus(device.getStatus().name());
        snapshot.setAgentVersion(device.getAgentVersion());
        snapshot.setLastHeartbeat(device.getLastHeartbeat());
        snapshot.setSupportedProjects(device.getSupportedProjects());
        snapshot.setDeviceGroupId(device.getDeviceGroupId());
        snapshot.setOnlineStrategy(device.getOnlineStrategy());
        snapshot.setAttributes(device.getAttributes());
        snapshot.setCreateTime(device.getCreateTime());
        snapshot.setUpdateTime(device.getUpdateTime());
        snapshot.setAppCount(device.getSupportedProjects() != null ? device.getSupportedProjects().size() : 0);
        snapshot.setDeviceAvailable(device.isAvailable());
        snapshot.setDeviceLocked(deviceLocked);
        return snapshot;
    }

    /**
     * 鍒涘缓Token蹇収
     */
    private TokenSnapshot createTokenSnapshot(Token token) {
        TokenSnapshot snapshot = new TokenSnapshot();
        snapshot.setTokenId(token.getTokenId());
        snapshot.setDeviceId(token.getDeviceId());
        snapshot.setTokenStatus(token.getStatus().name());
        snapshot.setChannel(token.getChannel());
        snapshot.setAttributes(token.getAttributes());
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
     * 鑾峰彇浠诲姟鐨勬墍鏈夊垎閰嶈褰?
     */
    public List<AssignmentRecord> getRecordsByTaskId(String taskId) {
        return records.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .collect(Collectors.toList());
    }

    /**
     * 鑾峰彇璁惧鐨勬墍鏈夊垎閰嶈褰?
     */
    public List<AssignmentRecord> getRecordsByDeviceId(String deviceId) {
        return records.values().stream()
                .filter(r -> deviceId.equals(r.getDeviceId()))
                .collect(Collectors.toList());
    }

    /**
     * 鑾峰彇鎴愬姛鐨勫垎閰嶈褰?
     */
    public List<AssignmentRecord> getSuccessfulRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 鑾峰彇澶辫触鐨勫垎閰嶈褰?
     */
    public List<AssignmentRecord> getFailedRecords() {
        return records.values().stream()
                .filter(r -> !AssignmentResult.SUCCESS.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 鑾峰彇瑙勫垯涓嶅尮閰嶇殑璁板綍
     */
    public List<AssignmentRecord> getRuleNotMatchRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.RULE_NOT_MATCH.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 鑾峰彇鍐茬獊璁板綍
     */
    public List<AssignmentRecord> getConflictRecords() {
        return records.values().stream()
                .filter(r -> AssignmentResult.CONFLICT.equals(r.getResult()))
                .collect(Collectors.toList());
    }

    /**
     * 鐢熸垚鍒嗛厤缁熻鎶ュ憡
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

        // 鎸変换鍔″垎缁勭粺璁?
        Map<String, Long> taskStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getTaskId, Collectors.counting()));
        report.put("taskStats", taskStats);

        // 鎸夎澶囧垎缁勭粺璁?
        Map<String, Long> deviceStats = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getDeviceId, Collectors.counting()));
        report.put("deviceStats", deviceStats);

        return report;
    }

    /**
     * 妫€娴嬮噸澶?鍐茬獊缁戝畾
     */
    public List<Map<String, Object>> detectConflicts() {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        // 妫€娴嬪悓涓€璁惧鍦ㄥ悓涓€鏃堕棿娈电殑閲嶅鍒嗛厤
        Map<String, List<AssignmentRecord>> deviceRecords = records.values().stream()
                .collect(Collectors.groupingBy(AssignmentRecord::getDeviceId));

        for (Map.Entry<String, List<AssignmentRecord>> entry : deviceRecords.entrySet()) {
            String deviceId = entry.getKey();
            List<AssignmentRecord> deviceRecordList = entry.getValue();

            // 鎸夋椂闂存帓搴?
            deviceRecordList.sort(Comparator.comparing(AssignmentRecord::getAssignTime));

            // 妫€鏌ユ槸鍚︽湁鏃堕棿閲嶅彔鐨勬垚鍔熷垎閰?
            for (int i = 0; i < deviceRecordList.size() - 1; i++) {
                AssignmentRecord current = deviceRecordList.get(i);
                AssignmentRecord next = deviceRecordList.get(i + 1);

                if (AssignmentResult.SUCCESS.equals(current.getResult()) &&
                        AssignmentResult.SUCCESS.equals(next.getResult())) {

                    // 妫€鏌ユ椂闂撮棿闅旀槸鍚﹁繃鐭紙鍙兘瀛樺湪鍐茬獊锛?
                    long timeDiff = java.time.Duration.between(current.getAssignTime(), next.getAssignTime()).toMinutes();
                    if (timeDiff < 5) { // 5鍒嗛挓鍐呴噸澶嶅垎閰嶈涓烘綔鍦ㄥ啿绐?
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
