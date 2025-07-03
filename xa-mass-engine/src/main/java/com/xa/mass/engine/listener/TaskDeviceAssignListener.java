package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.DeviceMatchContext;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务分配监听器：监听任务分配事件，按批次分配设备
 */
public class TaskDeviceAssignListener {
    private static final Logger log = LoggerFactory.getLogger(TaskDeviceAssignListener.class);
    private final RuleManager<Map<String, Object>> ruleManager;
    private final DeviceManager deviceManager;
    private final TaskMsgAssignListener msgAssignListener;
    private final AssignmentRecordService recordService;

    public TaskDeviceAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    DeviceManager deviceManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService) {
        this.ruleManager = ruleManager;
        this.deviceManager = deviceManager;
        this.msgAssignListener = msgAssignListener;
        this.recordService = recordService;
    }

    /**
     * 监听到任务分配事件，进行一批设备分配
     */
    public void onTaskAssign(Task task) {
        int maxDeviceCount = (int) Math.ceil((double) task.getTaskInitNumber() / task.getBatchSize());
        int batchSize = Math.max(task.getRunTaskMinDeviceCnt(), maxDeviceCount);
        List<Device> matched = matchDevicesWithRules(task, batchSize);
        if (!matched.isEmpty()) {
            // 推送到消息分配监听器
            msgAssignListener.onMsgAssign(task, matched);
        }
    }

    /**
     * 使用规则引擎匹配设备
     */
    List<Device> matchDevicesWithRules(Task task, int maxDeviceCount) {
        List<Device> matchedDevices = new ArrayList<>();
        List<Device> candidates = deviceManager.getDevicesByCountry(task.getTaskCountry());
        List<RuleDefinition> rules = ruleManager.getDefaultRules();

        log.info("[DeviceAssign] Matching devices for task {} (country: {}, candidates: {}, rules: {})",
                task.getTid(), task.getTaskCountry(), candidates.size(), rules.size());

        // 显示规则信息（仅在debug级别）
        if (log.isDebugEnabled()) {
            for (RuleDefinition rule : rules) {
                log.debug("[DeviceAssign] Rule: {} - {}", rule.getId(), rule.getContent());
            }
        }

        for (Device device : candidates) {
            if (matchedDevices.size() >= maxDeviceCount) {
                log.info("[DeviceAssign] Max device count {} reached for task {}, stopping matching",
                        maxDeviceCount, task.getTid());
                break;
            }

            // 获取设备的Token
            Token token = deviceManager.getToken(device.getDeviceId());

            // 创建设备匹配上下文
            DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task, deviceManager);

            // 打印设备和Token详细信息（仅在debug级别）
            if (log.isDebugEnabled()) {
                log.debug("[Debug] DeviceId={}, groupId={}, status={}, locked={}, supportedProjects={}, tokenId={}, tokenStatus={}, tokenChannel={}",
                        device.getDeviceId(),
                        device.getGroupId(),
                        device.getStatus(),
                        deviceManager.isLocked(device.getDeviceId()),
                        device.getSupportedProjects().stream()
                                .map(Project::getCode)
                                .collect(Collectors.joining(", ")),
                        token != null ? token.getTokenId() : "null",
                        token != null ? token.getStatus() : "null",
                        token != null ? token.getChannel() : "null"
                );
                log.debug("[Debug] DeviceMatchContext: {}", matchContext.getContext());
            }

            try {
                // 使用规则引擎评估设备是否匹配
                List<String> hitRules = ruleManager.evaluateDefaultRules(matchContext.getContext());
                List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext);

                log.debug("[DeviceAssign] Device {} - Hit rules: {}/{}",
                        device.getDeviceId(), hitRules.size(), rules.size());

                // 如果所有规则都通过，则匹配成功
                if (hitRules.size() == rules.size()) {
                    if (deviceManager.tryLockDevice(device.getDeviceId())) {
                        // 记录成功分配
                        recordService.recordDeviceAssignment(
                                task, device, token, AssignmentResult.SUCCESS,
                                "所有规则匹配成功，设备锁定成功", ruleEvaluations, matchContext.getContext()
                        );
                        matchedDevices.add(device);
                        log.info("✓ Device matched: {} for task {}", device.getDeviceId(), task.getTid());
                    } else {
                        // 记录设备锁定失败
                        recordService.recordDeviceAssignment(
                                task, device, token, AssignmentResult.CONFLICT,
                                "设备已被锁定，无法分配", ruleEvaluations, matchContext.getContext()
                        );
                        log.info("✗ Device locked: {}", device.getDeviceId());
                    }
                } else {
                    // 记录规则不匹配
                    String failedRules = ruleEvaluations.stream()
                            .filter(r -> !r.isPassed())
                            .map(RuleEvaluationDetail::getRuleId)
                            .collect(java.util.stream.Collectors.joining(", "));
                    recordService.recordDeviceAssignment(
                            task, device, token, AssignmentResult.RULE_NOT_MATCH,
                            "规则不匹配: " + failedRules, ruleEvaluations, matchContext.getContext()
                    );
                    log.info("✗ Rule not matched: {} (failed rules: {})", device.getDeviceId(), failedRules);

                    // 显示失败的规则详情（仅在debug级别）
                    if (log.isDebugEnabled()) {
                        for (RuleEvaluationDetail detail : ruleEvaluations) {
                            if (!detail.isPassed()) {
                                log.debug("[DeviceAssign] Failed rule: {} - {} = {}",
                                        detail.getRuleId(), detail.getRuleContent(), detail.getEvaluationResult());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 记录评估异常
                recordService.recordDeviceAssignment(
                        task, device, token, AssignmentResult.FAILED,
                        "规则评估异常: " + e.getMessage(), new ArrayList<>(), matchContext.getContext()
                );
                log.error("Error evaluating rules for device {}: {}", device.getDeviceId(), e.getMessage());
            }
        }

        log.info("[DeviceAssign] Total matched devices: {} for task {}", matchedDevices.size(), task.getTid());
        return matchedDevices;
    }

    /**
     * 详细评估每个规则
     */
    private List<RuleEvaluationDetail> evaluateRulesWithDetails(DeviceMatchContext matchContext) {
        List<RuleEvaluationDetail> evaluations = new ArrayList<>();
        List<RuleDefinition> rules = ruleManager.getDefaultRules();

        for (RuleDefinition rule : rules) {
            long startTime = System.currentTimeMillis();
            boolean passed = false;
            String result = "false";

            try {
                passed = ruleManager.evaluate(rule, matchContext.getContext());
                result = String.valueOf(passed);

                // 只记录失败的规则匹配（成功的用debug级别）
                if (!passed) {
                    log.info("[Debug] Rule: {} ({}), result: ✗ 失败", rule.getId(), rule.getDesc());
                } else {
                    log.debug("[Debug] Rule: {} ({}), result: ✓ 通过", rule.getId(), rule.getDesc());
                }
            } catch (Exception e) {
                result = "Exception: " + e.getMessage();
                log.info("[Debug] Rule: {} ({}), result: ✗ 异常 - {}", rule.getId(), rule.getDesc(), e.getMessage());
            }

            long evaluationTime = System.currentTimeMillis() - startTime;

            RuleEvaluationDetail detail = new RuleEvaluationDetail(
                    rule.getId(), rule.getContent(), rule.getDesc(),
                    passed, result, evaluationTime
            );
            evaluations.add(detail);
        }

        return evaluations;
    }
} 