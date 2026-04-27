package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default matching strategy backed by the current rule engine.
 */
public class RuleBasedTaskWorkerMatchingStrategy implements TaskWorkerMatchingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedTaskWorkerMatchingStrategy.class);

    private final RuleManager<Map<String, Object>> ruleManager;
    private final WorkerManager workerManager;
    private final AssignmentRecordService recordService;

    public RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                               WorkerManager workerManager,
                                               AssignmentRecordService recordService) {
        this.ruleManager = ruleManager;
        this.workerManager = workerManager;
        this.recordService = recordService;
    }

    @Override
    public List<MatchedWorkerContext> matchWorkers(Task task, int maxWorkerCount) {
        List<MatchedWorkerContext> matchedWorkers = new ArrayList<>();
        List<Worker> candidates = workerManager.findWorkerCandidates(task);
        List<RuleDefinition> rules = ruleManager.getDefaultRules();

        log.info("[WorkerAssign] Matching workers for task {} (routingCode: {}, candidates: {}, rules: {})",
                task.getTid(), TaskSharedConfig.routingCode(task), candidates.size(), rules.size());

        if (log.isDebugEnabled()) {
            for (RuleDefinition rule : rules) {
                log.debug("[WorkerAssign] Rule: {} - {}", rule.getId(), rule.getContent());
            }
        }

        for (Worker worker : candidates) {
            if (matchedWorkers.size() >= maxWorkerCount) {
                log.info("[WorkerAssign] Max worker count {} reached for task {}, stopping matching",
                        maxWorkerCount, task.getTid());
                break;
            }

            List<WorkerContext> workerContexts = workerManager.getWorkerContexts(worker.getWorkerId());
            if (workerContexts.isEmpty()) {
                workerContexts = new ArrayList<>();
                workerContexts.add(null);
            }

            for (WorkerContext workerContext : workerContexts) {
                PrefilterDecision prefilterDecision = prefilterCandidate(task, worker, workerContext);
                if (!prefilterDecision.passed()) {
                    TraceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                            prefilterDecision.reason());
                    recordService.recordWorkerAssignment(
                            task, worker, workerContext, prefilterDecision.result(),
                            prefilterDecision.reason(),
                            new ArrayList<>(), prefilterDecision.contextSnapshot(),
                            prefilterDecision.workerLocked()
                    );
                    log.info("Worker candidate rejected before rule evaluation: {} context {} ({})",
                            worker.getWorkerId(),
                            workerContext != null ? workerContext.getWorkerContextId() : "null",
                            prefilterDecision.reason());
                    continue;
                }

                WorkerMatchContext matchContext = new WorkerMatchContext(worker, workerContext, task, workerManager);

                if (log.isDebugEnabled()) {
                    log.debug("[Debug] WorkerId={}, workerGroupId={}, status={}, locked={}, supportedProjects={}, workerContextId={}, workerContextStatus={}, workerContextChannel={}",
                            worker.getWorkerId(),
                            worker.getWorkerGroupId(),
                            worker.getStatus(),
                            workerManager.isLocked(worker.getWorkerId()),
                            String.join(", ", worker.getSupportedProjects()),
                            workerContext != null ? workerContext.getWorkerContextId() : "null",
                            workerContext != null ? workerContext.getStatus() : "null",
                            workerContext != null ? workerContext.getRoutingTags() : "null"
                    );
                    log.debug("[Debug] WorkerMatchContext: {}", matchContext.getContext());
                }

                try {
                    List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext);
                    long hitCount = ruleEvaluations.stream().filter(RuleEvaluationDetail::isPassed).count();

                    log.debug("[WorkerAssign] Worker {} context {} - Hit rules: {}/{}",
                            worker.getWorkerId(),
                            workerContext != null ? workerContext.getWorkerContextId() : "null",
                            hitCount,
                            rules.size());

                    if (hitCount == rules.size()) {
                        if (workerManager.tryLockWorker(worker.getWorkerId())) {
                            TraceEventLogger.workerLockAcquired(task.getTid(), worker.getWorkerId(),
                                    "TRY_LOCK_WORKER", "RuleBasedTaskWorkerMatchingStrategy",
                                    "all rules matched");
                            TraceEventLogger.workerMatchAccepted(task.getTid(), worker, workerContext,
                                    "all rules matched and worker lock acquired");
                            recordService.recordWorkerAssignment(
                                    task, worker, workerContext, AssignmentResult.SUCCESS,
                                    "all rules matched and worker lock acquired",
                                    ruleEvaluations, matchContext.getContext(), true
                            );
                            matchedWorkers.add(new MatchedWorkerContext(worker, workerContext));
                            log.info("Worker matched: {} with context {} for task {}",
                                    worker.getWorkerId(),
                                    workerContext != null ? workerContext.getWorkerContextId() : "null",
                                    task.getTid());
                        } else {
                            TraceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                                    "worker lock conflict after rules matched");
                            recordService.recordWorkerAssignment(
                                    task, worker, workerContext, AssignmentResult.CONFLICT,
                                    "worker lock conflict after rules matched",
                                    ruleEvaluations, matchContext.getContext(),
                                    workerManager.isLocked(worker.getWorkerId())
                            );
                            log.info("Worker locked: {}", worker.getWorkerId());
                        }
                        break;
                    }

                    String failedRules = ruleEvaluations.stream()
                            .filter(r -> !r.isPassed())
                            .map(RuleEvaluationDetail::getRuleId)
                            .collect(Collectors.joining(", "));
                    TraceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                            "rule evaluation failed: " + failedRules);
                    recordService.recordWorkerAssignment(
                            task, worker, workerContext, AssignmentResult.RULE_NOT_MATCH,
                            "rule evaluation failed: " + failedRules,
                            ruleEvaluations, matchContext.getContext(),
                            workerManager.isLocked(worker.getWorkerId())
                    );
                    log.info("Rule not matched: {} context {} (failed rules: {})",
                            worker.getWorkerId(),
                            workerContext != null ? workerContext.getWorkerContextId() : "null",
                            failedRules);

                    if (log.isDebugEnabled()) {
                        for (RuleEvaluationDetail detail : ruleEvaluations) {
                            if (!detail.isPassed()) {
                                log.debug("[WorkerAssign] Failed rule: {} - {} = {}",
                                        detail.getRuleId(), detail.getRuleContent(), detail.getEvaluationResult());
                            }
                        }
                    }
                } catch (Exception e) {
                    TraceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                            "rule evaluation exception: " + e.getMessage());
                    recordService.recordWorkerAssignment(
                            task, worker, workerContext, AssignmentResult.FAILED,
                            "rule evaluation exception: " + e.getMessage(),
                            new ArrayList<>(), matchContext.getContext(),
                            workerManager.isLocked(worker.getWorkerId())
                    );
                    log.error("Error evaluating rules for worker {} context {}: {}",
                            worker.getWorkerId(),
                            workerContext != null ? workerContext.getWorkerContextId() : "null",
                            e.getMessage());
                }
            }
        }

        log.info("[WorkerAssign] Total matched worker-context candidates: {} for task {}",
                matchedWorkers.size(), task.getTid());
        return matchedWorkers;
    }

    private PrefilterDecision prefilterCandidate(Task task, Worker worker, WorkerContext workerContext) {
        boolean workerLocked = workerManager.isLocked(worker.getWorkerId());
        Map<String, Object> contextSnapshot = buildPrefilterContextSnapshot(task, worker, workerContext, workerLocked);
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        boolean sdkEventTask = eventCode != null && !eventCode.isBlank();

        if (!worker.isAvailable()) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker unavailable", contextSnapshot, workerLocked);
        }
        if (workerLocked) {
            return PrefilterDecision.reject(AssignmentResult.CONFLICT,
                    "worker locked", contextSnapshot, true);
        }
        if (targetWorkerId != null && !targetWorkerId.equals(worker.getWorkerId())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker mismatch", contextSnapshot, false);
        }
        if (!targetWorkerAttributes.isEmpty()
                && !workerAttributesMatch(worker.getAttributes(), targetWorkerAttributes)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker attributes mismatch", contextSnapshot, false);
        }
        if (!sdkEventTask && !worker.supportsProject(task.getProject())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "project not supported", contextSnapshot, false);
        }
        if (sdkEventTask && !worker.supportsEvent(eventCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "event not supported", contextSnapshot, false);
        }

        String routingCode = TaskSharedConfig.routingCode(task);
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        if (workerContext == null) {
            if (taskHasRoutingRequirement) {
                return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                        "routing code mismatch", contextSnapshot, false);
            }
            return PrefilterDecision.allow();
        }
        if (!workerContext.isAllocatable()) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "workerContext not allocatable", contextSnapshot, false);
        }
        if (workerContext.getProject() != null && !workerContext.getProject().equals(task.getProject())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "workerContext project mismatch", contextSnapshot, false);
        }
        if (taskHasRoutingRequirement && !workerContext.getRoutingTags().contains(routingCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "routing code mismatch", contextSnapshot, false);
        }
        return PrefilterDecision.allow();
    }

    private Map<String, Object> buildPrefilterContextSnapshot(Task task,
                                                              Worker worker,
                                                              WorkerContext workerContext,
                                                              boolean workerLocked) {
        Map<String, Object> context = new LinkedHashMap<>();
        String routingCode = TaskSharedConfig.routingCode(task);
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        boolean sdkEventTask = eventCode != null && !eventCode.isBlank();
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        Set<String> routingTags = workerContext != null ? workerContext.getRoutingTags() : Set.of();

        context.put("workerId", worker.getWorkerId());
        context.put("workerStatus", worker.getStatus().name());
        context.put("workerGroupId", worker.getWorkerGroupId());
        context.put("workerAttributes", worker.getAttributes());
        context.put("agentVersion", worker.getAgentVersion());
        context.put("supportedProjects", worker.getSupportedProjects());
        context.put("supportedEventCodes", worker.getSupportedEventCodes());
        context.put("isWorkerAvailable", worker.isAvailable());
        context.put("isWorkerLocked", workerLocked);

        context.put("taskId", task.getTid());
        context.put("taskName", task.getTaskName());
        context.put("taskProject", task.getProject());
        context.put("taskEventCode", eventCode);
        context.put("taskUsesEventCapability", sdkEventTask);
        context.put("taskTargetWorkerId", targetWorkerId);
        context.put("taskTargetWorkerAttributes", targetWorkerAttributes);
        context.put("taskSharedConfig", task.getSharedConfig());
        context.put("routingCode", routingCode);
        context.put("taskHasRoutingRequirement", taskHasRoutingRequirement);
        context.put("taskStatus", task.getStatus().name());
        context.put("taskTargetNumber", task.getTaskTargetNumber());
        context.put("batchSize", task.getBatchSize());
        context.put("minRequiredWorkerCount", task.getMinRequiredWorkerCount());
        context.put("appCount", worker.getSupportedProjects() != null ? worker.getSupportedProjects().size() : 0);
        context.put("supportsProject", worker.supportsProject(task.getProject()));
        context.put("supportsEvent", !sdkEventTask || worker.supportsEvent(eventCode));
        context.put("matchesTargetWorkerId", targetWorkerId == null || targetWorkerId.equals(worker.getWorkerId()));
        context.put("matchesTargetWorkerAttributes",
                targetWorkerAttributes.isEmpty() || workerAttributesMatch(worker.getAttributes(), targetWorkerAttributes));

        if (workerContext == null) {
            context.put("hasWorkerContext", false);
            context.put("workerContextId", null);
            context.put("workerContextProject", null);
            context.put("workerContextStatus", null);
            context.put("workerContextRoutingTags", Set.of());
            context.put("workerContextAttributes", Map.of());
            context.put("isWorkerContextAllocatable", false);
            context.put("isWorkerContextAvailable", false);
            context.put("isWorkerContextUsable", false);
            context.put("isWorkerContextReserved", false);
            context.put("isWorkerContextOccupied", false);
            context.put("workerContextProjectMatchesTaskProject", false);
            context.put("workerContextMatchesRoutingCode", false);
            return context;
        }

        context.put("hasWorkerContext", true);
        context.put("workerContextId", workerContext.getWorkerContextId());
        context.put("workerContextProject", workerContext.getProject());
        context.put("workerContextStatus", workerContext.getStatus().name());
        context.put("workerContextRoutingTags", routingTags);
        context.put("workerContextAttributes", workerContext.getAttributes());
        context.put("isWorkerContextAllocatable", workerContext.isAllocatable());
        context.put("isWorkerContextAvailable", workerContext.isAvailable());
        context.put("isWorkerContextUsable", workerContext.isUsable());
        context.put("isWorkerContextReserved", workerContext.isReserved());
        context.put("isWorkerContextOccupied", workerContext.isOccupied());
        context.put("workerContextProjectMatchesTaskProject",
                workerContext.getProject() != null && workerContext.getProject().equals(task.getProject()));
        context.put("workerContextMatchesRoutingCode",
                taskHasRoutingRequirement && routingTags.contains(routingCode));
        return context;
    }

    private boolean workerAttributesMatch(Map<String, String> workerAttributes,
                                          Map<String, String> requiredAttributes) {
        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return true;
        }
        if (workerAttributes == null || workerAttributes.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : requiredAttributes.entrySet()) {
            if (!Objects.equals(workerAttributes.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private List<RuleEvaluationDetail> evaluateRulesWithDetails(WorkerMatchContext matchContext) {
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
                    log.info("[Debug] Rule: {} ({}), result: FAIL", rule.getId(), rule.getDescription());
                } else {
                    log.debug("[Debug] Rule: {} ({}), result: PASS", rule.getId(), rule.getDescription());
                }
            } catch (Exception e) {
                result = "Exception: " + e.getMessage();
                log.info("[Debug] Rule: {} ({}), result: EXCEPTION - {}",
                        rule.getId(), rule.getDescription(), e.getMessage());
            }

            long evaluationTime = System.currentTimeMillis() - startTime;
            evaluations.add(new RuleEvaluationDetail(
                    rule.getId(), rule.getContent(), rule.getDescription(),
                    passed, result, evaluationTime
            ));
        }

        return evaluations;
    }

    private static final class PrefilterDecision {
        private final boolean passed;
        private final AssignmentResult result;
        private final String reason;
        private final Map<String, Object> contextSnapshot;
        private final boolean workerLocked;

        private PrefilterDecision(boolean passed,
                                  AssignmentResult result,
                                  String reason,
                                  Map<String, Object> contextSnapshot,
                                  boolean workerLocked) {
            this.passed = passed;
            this.result = result;
            this.reason = reason;
            this.contextSnapshot = contextSnapshot;
            this.workerLocked = workerLocked;
        }

        private static PrefilterDecision allow() {
            return new PrefilterDecision(true, null, null, Map.of(), false);
        }

        private static PrefilterDecision reject(AssignmentResult result,
                                                String reason,
                                                Map<String, Object> contextSnapshot,
                                                boolean workerLocked) {
            return new PrefilterDecision(false, result, reason, contextSnapshot, workerLocked);
        }

        private boolean passed() {
            return passed;
        }

        private AssignmentResult result() {
            return result;
        }

        private String reason() {
            return reason;
        }

        private Map<String, Object> contextSnapshot() {
            return contextSnapshot;
        }

        private boolean workerLocked() {
            return workerLocked;
        }
    }
}
