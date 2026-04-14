package com.xa.mass.engine.strategy;

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
 * Default matching strategy backed by the current rule engine.
 */
public class RuleBasedTaskDeviceMatchingStrategy implements TaskDeviceMatchingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedTaskDeviceMatchingStrategy.class);

    private final RuleManager<Map<String, Object>> ruleManager;
    private final DeviceManager deviceManager;
    private final AssignmentRecordService recordService;

    public RuleBasedTaskDeviceMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                               DeviceManager deviceManager,
                                               AssignmentRecordService recordService) {
        this.ruleManager = ruleManager;
        this.deviceManager = deviceManager;
        this.recordService = recordService;
    }

    @Override
    public List<Device> matchDevices(Task task, int maxDeviceCount) {
        List<Device> matchedDevices = new ArrayList<>();
        List<Device> candidates = deviceManager.getAllDevices();
        List<RuleDefinition> rules = ruleManager.getDefaultRules();

        log.info("[DeviceAssign] Matching devices for task {} (routingCountryCode: {}, candidates: {}, rules: {})",
                task.getTid(), task.getTaskRoutingCountryCode(), candidates.size(), rules.size());

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

            Token token = deviceManager.getToken(device.getDeviceId());
            DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task, deviceManager);

            if (log.isDebugEnabled()) {
                log.debug("[Debug] DeviceId={}, deviceGroupId={}, status={}, locked={}, supportedProjects={}, tokenId={}, tokenStatus={}, tokenChannel={}",
                        device.getDeviceId(),
                        device.getDeviceGroupId(),
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
                List<String> hitRules = ruleManager.evaluateDefaultRules(matchContext.getContext());
                List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext);

                log.debug("[DeviceAssign] Device {} - Hit rules: {}/{}",
                        device.getDeviceId(), hitRules.size(), rules.size());

                if (hitRules.size() == rules.size()) {
                    if (deviceManager.tryLockDevice(device.getDeviceId())) {
                        recordService.recordDeviceAssignment(
                                task, device, token, AssignmentResult.SUCCESS,
                                "鎵€鏈夎鍒欏尮閰嶆垚鍔燂紝璁惧閿佸畾鎴愬姛", ruleEvaluations, matchContext.getContext()
                        );
                        matchedDevices.add(device);
                        log.info("Device matched: {} for task {}", device.getDeviceId(), task.getTid());
                    } else {
                        recordService.recordDeviceAssignment(
                                task, device, token, AssignmentResult.CONFLICT,
                                "璁惧宸茶閿佸畾锛屾棤娉曞垎閰?, ruleEvaluations, matchContext.getContext()
                        );
                        log.info("Device locked: {}", device.getDeviceId());
                    }
                } else {
                    String failedRules = ruleEvaluations.stream()
                            .filter(r -> !r.isPassed())
                            .map(RuleEvaluationDetail::getRuleId)
                            .collect(Collectors.joining(", "));
                    recordService.recordDeviceAssignment(
                            task, device, token, AssignmentResult.RULE_NOT_MATCH,
                            "瑙勫垯涓嶅尮閰? " + failedRules, ruleEvaluations, matchContext.getContext()
                    );
                    log.info("Rule not matched: {} (failed rules: {})", device.getDeviceId(), failedRules);

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
                recordService.recordDeviceAssignment(
                        task, device, token, AssignmentResult.FAILED,
                        "瑙勫垯璇勪及寮傚父: " + e.getMessage(), new ArrayList<>(), matchContext.getContext()
                );
                log.error("Error evaluating rules for device {}: {}", device.getDeviceId(), e.getMessage());
            }
        }

        log.info("[DeviceAssign] Total matched devices: {} for task {}", matchedDevices.size(), task.getTid());
        return matchedDevices;
    }

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

                if (!passed) {
                    log.info("[Debug] Rule: {} ({}), result: 澶辫触", rule.getId(), rule.getDesc());
                } else {
                    log.debug("[Debug] Rule: {} ({}), result: 閫氳繃", rule.getId(), rule.getDesc());
                }
            } catch (Exception e) {
                result = "Exception: " + e.getMessage();
                log.info("[Debug] Rule: {} ({}), result: 寮傚父 - {}", rule.getId(), rule.getDesc(), e.getMessage());
            }

            long evaluationTime = System.currentTimeMillis() - startTime;
            evaluations.add(new RuleEvaluationDetail(
                    rule.getId(), rule.getContent(), rule.getDesc(),
                    passed, result, evaluationTime
            ));
        }

        return evaluations;
    }
}
