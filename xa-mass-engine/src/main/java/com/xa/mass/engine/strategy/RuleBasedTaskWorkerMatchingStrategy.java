package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
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
    private final AssignmentDiagnosticRecorder recordService;
    private final TraceEventLogger traceEventLogger;
    private final WorkerCandidateRanker candidateRanker;

    public RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                               WorkerManager workerManager,
                                               AssignmentDiagnosticRecorder recordService) {
        this(ruleManager, workerManager, recordService, TraceEventLogger.noop());
    }

    public RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                               WorkerManager workerManager,
                                               AssignmentDiagnosticRecorder recordService,
                                               TraceEventLogger traceEventLogger) {
        this(ruleManager, workerManager, recordService, traceEventLogger, new DefaultWorkerCandidateRanker());
    }

    public RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                               WorkerManager workerManager,
                                               AssignmentDiagnosticRecorder recordService,
                                               TraceEventLogger traceEventLogger,
                                               WorkerCandidateRanker candidateRanker) {
        this.ruleManager = ruleManager;
        this.workerManager = workerManager;
        this.recordService = recordService;
        this.traceEventLogger = traceEventLogger;
        this.candidateRanker = candidateRanker != null ? candidateRanker : new DefaultWorkerCandidateRanker();
    }

    @Override
    public List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount) {
        List<WorkerSchedulingCandidate> matchedWorkers = new ArrayList<>();
        if (maxWorkerCount <= 0) {
            return matchedWorkers;
        }
        List<Worker> candidates = workerManager.findWorkerCandidates(task);
        List<RuleDefinition> rules = ruleManager.getDefaultRules();
        List<RulePassedCandidate> rulePassedCandidates = new ArrayList<>();

        log.info("[WorkerAssign] Matching workers for task {} (routingCode: {}, candidates: {}, rules: {})",
                task.getTid(), TaskSharedConfig.routingCode(task), candidates.size(), rules.size());

        if (log.isDebugEnabled()) {
            for (RuleDefinition rule : rules) {
                log.debug("[WorkerAssign] Rule: {} - {}", rule.getId(), rule.getContent());
            }
        }

        List<WorkerSchedulingCandidate> schedulingCandidates = enumerateSchedulingCandidates(candidates);
        Set<String> completedWorkerIds = new HashSet<>();

        for (WorkerSchedulingCandidate candidate : schedulingCandidates) {
            Worker worker = candidate.getWorker();
            WorkerContext workerContext = candidate.getWorkerContext();
            if (completedWorkerIds.contains(worker.getWorkerId())) {
                continue;
            }

            PrefilterDecision prefilterDecision = prefilterCandidate(task, candidate);
            if (!prefilterDecision.passed()) {
                traceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                        prefilterDecision.reason());
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, prefilterDecision.result(),
                        prefilterDecision.reason(),
                        new ArrayList<>(), prefilterDecision.contextSnapshot(),
                        prefilterDecision.workerLocked()
                );
                log.debug("Worker candidate rejected before rule evaluation: {} context {} ({})",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        prefilterDecision.reason());
                continue;
            }

            WorkerMatchContext matchContext = new WorkerMatchContext(candidate, task);

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
                List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext, rules);
                long hitCount = ruleEvaluations.stream().filter(RuleEvaluationDetail::isPassed).count();

                log.debug("[WorkerAssign] Worker {} context {} - Hit rules: {}/{}",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        hitCount,
                        rules.size());

                if (hitCount == rules.size()) {
                    rulePassedCandidates.add(new RulePassedCandidate(candidate, matchContext, ruleEvaluations));
                    completedWorkerIds.add(worker.getWorkerId());
                    continue;
                }

                String failedRules = ruleEvaluations.stream()
                        .filter(r -> !r.isPassed())
                        .map(RuleEvaluationDetail::getRuleId)
                        .collect(Collectors.joining(", "));
                traceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
                        "rule evaluation failed: " + failedRules);
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, AssignmentResult.RULE_NOT_MATCH,
                        "rule evaluation failed: " + failedRules,
                        ruleEvaluations, matchContext.getContext(),
                        workerManager.isLocked(worker.getWorkerId())
                );
                log.debug("Rule not matched: {} context {} (failed rules: {})",
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
                traceEventLogger.workerMatchRejected(task.getTid(), worker, workerContext,
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

        Map<WorkerMatchContext, RulePassedCandidate> passedByContext = new IdentityHashMap<>();
        List<WorkerMatchContext> contextsToRank = new ArrayList<>();
        for (RulePassedCandidate passedCandidate : rulePassedCandidates) {
            contextsToRank.add(passedCandidate.matchContext());
            passedByContext.put(passedCandidate.matchContext(), passedCandidate);
        }
        List<WorkerMatchContext> rankedContexts = candidateRanker.rank(contextsToRank, task);
        int rank = 0;
        for (WorkerMatchContext rankedContext : rankedContexts) {
            RulePassedCandidate passedCandidate = passedByContext.get(rankedContext);
            if (passedCandidate == null) {
                continue;
            }
            rank++;
            if (matchedWorkers.size() >= maxWorkerCount) {
                log.info("[WorkerAssign] Max worker count {} reached for task {}, stopping ranked matching",
                        maxWorkerCount, task.getTid());
                break;
            }
            WorkerSchedulingCandidate candidate = passedCandidate.candidate();
            Worker worker = candidate.getWorker();
            WorkerContext workerContext = candidate.getWorkerContext();
            double candidateScore = rankScore(rankedContext, task);
            boolean foreground = task.getExecutionSpec().isForeground();
            if (!workerManager.tryReserveWorkerCapacity(worker.getWorkerId(), task.getTid())) {
                traceEventLogger.workerMatchRejected(task.getTid(), candidate,
                        "worker capacity unavailable after candidate ranking", rank, candidateScore,
                        workerManager.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, AssignmentResult.QUOTA_EXCEEDED,
                        "worker capacity unavailable after candidate ranking",
                        passedCandidate.ruleEvaluations(), rankedContext.getContext(),
                        workerManager.isLocked(worker.getWorkerId())
                );
                log.debug("Worker capacity unavailable after candidate ranking: {}", worker.getWorkerId());
                continue;
            }
            if (!foreground) {
                traceEventLogger.workerMatchAccepted(task.getTid(), candidate,
                        "all rules matched and worker capacity reserved after candidate ranking", rank, candidateScore,
                        workerManager.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, AssignmentResult.SUCCESS,
                        "all rules matched and worker capacity reserved after candidate ranking",
                        passedCandidate.ruleEvaluations(), rankedContext.getContext(), false
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched without exclusive lock: {} with context {} for background task {} at rank {}",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        task.getTid(),
                        rank);
                continue;
            }
            if (workerManager.tryLockWorker(worker.getWorkerId())) {
                traceEventLogger.workerLockAcquired(task.getTid(), worker.getWorkerId(),
                        "TRY_LOCK_WORKER", "RuleBasedTaskWorkerMatchingStrategy",
                        "all rules matched after candidate ranking");
                traceEventLogger.workerMatchAccepted(task.getTid(), candidate,
                        "all rules matched and worker lock acquired after candidate ranking", rank, candidateScore,
                        workerManager.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, AssignmentResult.SUCCESS,
                        "all rules matched and worker lock acquired after candidate ranking",
                        passedCandidate.ruleEvaluations(), rankedContext.getContext(), true
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched: {} with context {} for task {} at rank {}",
                        worker.getWorkerId(),
                        workerContext != null ? workerContext.getWorkerContextId() : "null",
                        task.getTid(),
                        rank);
            } else {
                workerManager.releaseWorkerReservation(worker.getWorkerId(), task.getTid());
                traceEventLogger.workerMatchRejected(task.getTid(), candidate,
                        "worker lock conflict after candidate ranking", rank, candidateScore,
                        workerManager.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, worker, workerContext, AssignmentResult.CONFLICT,
                        "worker lock conflict after candidate ranking",
                        passedCandidate.ruleEvaluations(), rankedContext.getContext(),
                        workerManager.isLocked(worker.getWorkerId())
                );
                log.debug("Worker locked after candidate ranking: {}", worker.getWorkerId());
            }
        }

        log.info("[WorkerAssign] Total matched worker scheduling candidates: {} for task {}",
                matchedWorkers.size(), task.getTid());
        return matchedWorkers;
    }

    private double rankScore(WorkerMatchContext context, Task task) {
        if (candidateRanker instanceof DefaultWorkerCandidateRanker defaultRanker) {
            return defaultRanker.score(context, task);
        }
        return Double.NaN;
    }

    private List<WorkerSchedulingCandidate> enumerateSchedulingCandidates(List<Worker> candidates) {
        List<String> candidateIds = candidates.stream().map(Worker::getWorkerId).toList();
        Map<String, List<WorkerContext>> contextsByWorkerId = workerManager.getWorkerContextsByWorkerIds(candidateIds).stream()
                .collect(Collectors.groupingBy(WorkerContext::getWorkerId));
        List<WorkerSchedulingCandidate> schedulingCandidates = new ArrayList<>();
        for (Worker worker : candidates) {
            List<WorkerContext> workerContexts = contextsByWorkerId.getOrDefault(worker.getWorkerId(), List.of());
            if (workerContexts.isEmpty()) {
                schedulingCandidates.add(toSchedulingCandidate(worker, null));
                continue;
            }
            for (WorkerContext workerContext : workerContexts) {
                schedulingCandidates.add(toSchedulingCandidate(worker, workerContext));
            }
        }
        return schedulingCandidates;
    }

    private WorkerSchedulingCandidate toSchedulingCandidate(Worker worker, WorkerContext workerContext) {
        WorkerReachabilityState reachability = workerManager.getWorkerReachability(worker.getWorkerId());
        boolean dispatchEnabled = workerManager.isWorkerDispatchEnabled(worker);
        boolean workerLocked = workerManager.isLocked(worker.getWorkerId());
        return new WorkerSchedulingCandidate(
                worker,
                workerContext,
                WorkerSchedulingView.from(
                        worker,
                        workerContext,
                        reachability,
                        dispatchEnabled,
                        workerLocked,
                        workerManager.getWorkerLoad(worker.getWorkerId())
                )
        );
    }

    private PrefilterDecision prefilterCandidate(Task task, WorkerSchedulingCandidate candidate) {
        Worker worker = candidate.getWorker();
        WorkerSchedulingView schedulingView = candidate.getSchedulingView();
        WorkerReachabilityState reachability = schedulingView.reachability();
        if (!schedulingView.dispatchEnabled()) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker unavailable", Map.of(), false);
        }
        if (reachability != WorkerReachabilityState.ONLINE) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker transport unreachable", Map.of(
                            "transportReachability", reachability.name(),
                            "isTransportReachable", false
                    ), false);
        }
        boolean workerLocked = schedulingView.workerLocked();
        if (workerLocked) {
            return PrefilterDecision.reject(AssignmentResult.CONFLICT,
                    "worker locked", Map.of(), true);
        }
        Map<String, Object> contextSnapshot = buildPrefilterContextSnapshot(task, schedulingView);
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        boolean sdkEventTask = eventCode != null && !eventCode.isBlank();
        if (targetWorkerId != null && !targetWorkerId.equals(schedulingView.workerId())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker mismatch", contextSnapshot, false);
        }
        if (!targetWorkerAttributes.isEmpty()
                && !workerAttributesMatch(schedulingView.workerAttributes(), targetWorkerAttributes)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker attributes mismatch", contextSnapshot, false);
        }
        if (!sdkEventTask && !schedulingView.supportsProject(task.getProject())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "project not supported", contextSnapshot, false);
        }
        if (sdkEventTask && !schedulingView.supportsEvent(eventCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "event not supported", contextSnapshot, false);
        }

        String routingCode = TaskSharedConfig.routingCode(task);
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        if (!schedulingView.hasWorkerContext()) {
            if (taskHasRoutingRequirement) {
                return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                        "routing code mismatch", contextSnapshot, false);
            }
            return PrefilterDecision.allow();
        }
        if (!schedulingView.workerContextAllocatable()) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "workerContext not allocatable", contextSnapshot, false);
        }
        if (schedulingView.schedulingProject() != null && !schedulingView.schedulingProjectMatches(task.getProject())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "workerContext project mismatch", contextSnapshot, false);
        }
        if (taskHasRoutingRequirement && !schedulingView.schedulingRoutingTagsContain(routingCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "routing code mismatch", contextSnapshot, false);
        }
        return PrefilterDecision.allow();
    }

    private Map<String, Object> buildPrefilterContextSnapshot(Task task,
                                                              WorkerSchedulingView schedulingView) {
        Map<String, Object> context = new LinkedHashMap<>();
        String routingCode = TaskSharedConfig.routingCode(task);
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        boolean sdkEventTask = eventCode != null && !eventCode.isBlank();
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();

        context.put("workerId", schedulingView.workerId());
        context.put("workerStatus", schedulingView.workerStatusName());
        context.put("transportReachability", schedulingView.reachability().name());
        context.put("isTransportReachable", schedulingView.isTransportReachable());
        context.put("workerGroupId", schedulingView.workerGroupId());
        context.put("workerAttributes", schedulingView.workerAttributes());
        context.put("agentVersion", schedulingView.agentVersion());
        context.put("supportedProjects", schedulingView.supportedProjects());
        context.put("supportedEventCodes", schedulingView.supportedEventCodes());
        context.put("isWorkerAvailable", schedulingView.dispatchEnabled() && schedulingView.isTransportReachable());
        context.put("isWorkerLocked", schedulingView.workerLocked());
        context.put("workerActiveLeaseCount", schedulingView.activeLeaseCount());
        context.put("workerReservedCount", schedulingView.reservedCount());
        context.put("workerDeclaredCapacity", schedulingView.declaredCapacity());
        context.put("workerEstimatedLoadRatio", schedulingView.estimatedLoadRatio());
        context.put("currentActiveLeaseCount", schedulingView.activeLeaseCount());
        context.put("estimatedLoadRatio", schedulingView.estimatedLoadRatio());

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
        context.put("batchSize", task.getExecutionSpec().getBatchSize());
        context.put("minRequiredWorkerCount", task.getMinRequiredWorkerCount());
        context.put("appCount", schedulingView.supportedProjects().size());
        context.put("supportsProject", schedulingView.supportsProject(task.getProject()));
        context.put("supportsEvent", !sdkEventTask || schedulingView.supportsEvent(eventCode));
        context.put("matchesTargetWorkerId", targetWorkerId == null || targetWorkerId.equals(schedulingView.workerId()));
        context.put("matchesTargetWorkerAttributes",
                targetWorkerAttributes.isEmpty() || workerAttributesMatch(schedulingView.workerAttributes(), targetWorkerAttributes));
        context.put("workerSchedulingResourceId", schedulingView.schedulingResourceId());
        context.put("workerSchedulingProject", schedulingView.schedulingProject());
        context.put("workerSchedulingRoutingTags", schedulingView.schedulingRoutingTags());
        context.put("workerSchedulingAttributes", schedulingView.schedulingAttributes());
        context.put("hasWorkerSchedulingResource", schedulingView.hasWorkerContext());
        context.put("isWorkerSchedulingResourceAllocatable", schedulingView.schedulingResourceAllocatable());
        context.put("isWorkerSchedulingResourceAvailable", schedulingView.schedulingResourceAvailable());
        context.put("isWorkerSchedulingResourceUsable", schedulingView.schedulingResourceUsable());
        context.put("isWorkerSchedulingResourceReserved", schedulingView.schedulingResourceReserved());
        context.put("isWorkerSchedulingResourceOccupied", schedulingView.schedulingResourceOccupied());
        context.put("workerSchedulingProjectMatchesTaskProject",
                schedulingView.schedulingProjectMatches(task.getProject()));
        context.put("workerSchedulingMatchesRoutingCode",
                taskHasRoutingRequirement && schedulingView.schedulingRoutingTagsContain(routingCode));

        if (!schedulingView.hasWorkerContext()) {
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
            context.put("workerContextMatchesRoutingCode", context.get("workerSchedulingMatchesRoutingCode"));
            return context;
        }

        context.put("hasWorkerContext", true);
        context.put("workerContextId", schedulingView.workerContextId());
        context.put("workerContextProject", schedulingView.workerContextProject());
        context.put("workerContextStatus", schedulingView.workerContextStatusName());
        context.put("workerContextRoutingTags", schedulingView.workerContextRoutingTags());
        context.put("workerContextAttributes", schedulingView.workerContextAttributes());
        context.put("isWorkerContextAllocatable", schedulingView.workerContextAllocatable());
        context.put("isWorkerContextAvailable", schedulingView.workerContextAvailable());
        context.put("isWorkerContextUsable", schedulingView.workerContextUsable());
        context.put("isWorkerContextReserved", schedulingView.workerContextReserved());
        context.put("isWorkerContextOccupied", schedulingView.workerContextOccupied());
        context.put("workerContextProjectMatchesTaskProject",
                context.get("workerSchedulingProjectMatchesTaskProject"));
        context.put("workerContextMatchesRoutingCode", context.get("workerSchedulingMatchesRoutingCode"));
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

    private List<RuleEvaluationDetail> evaluateRulesWithDetails(WorkerMatchContext matchContext, List<RuleDefinition> rules) {
        List<RuleEvaluationDetail> evaluations = new ArrayList<>();

        for (RuleDefinition rule : rules) {
            long startTime = System.currentTimeMillis();
            boolean passed = false;
            String result = "false";

            try {
                passed = ruleManager.evaluate(rule, matchContext.getContext());
                result = String.valueOf(passed);

                if (!passed) {
                    log.debug("[Debug] Rule: {} ({}), result: FAIL", rule.getId(), rule.getDescription());
                } else {
                    log.debug("[Debug] Rule: {} ({}), result: PASS", rule.getId(), rule.getDescription());
                }
            } catch (Exception e) {
                result = "Exception: " + e.getMessage();
                log.debug("[Debug] Rule: {} ({}), result: EXCEPTION - {}",
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

    private record RulePassedCandidate(WorkerSchedulingCandidate candidate,
                                       WorkerMatchContext matchContext,
                                       List<RuleEvaluationDetail> ruleEvaluations) {
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
