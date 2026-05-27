package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.worker.WorkerAdmissionRuntime;
import com.xa.mass.engine.worker.WorkerCandidateBatch;
import com.xa.mass.engine.worker.WorkerCandidateRuntime;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.engine.worker.WorkerReachabilityState;
import com.xa.mass.engine.worker.WorkerSchedulingViewRuntime;
import com.xa.mass.engine.worker.WorkerTaskSelector;
import com.xa.mass.engine.worker.WorkerTaskSelectorFactory;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
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
public final class RuleBasedTaskWorkerMatchingStrategy implements TaskWorkerMatchingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedTaskWorkerMatchingStrategy.class);
    static final int DEFAULT_STAGE_ONE_SAMPLE_MIN =
            Integer.getInteger("xa.mass.engine.stageOneCandidateSampleMin", 512);
    static final int DEFAULT_STAGE_ONE_SAMPLE_MAX =
            Integer.getInteger("xa.mass.engine.stageOneCandidateSampleMax", 2_048);
    static final int DEFAULT_STAGE_ONE_OVERSAMPLE_FACTOR =
            Integer.getInteger("xa.mass.engine.stageOneCandidateOversampleFactor", 4);

    private final RuleManager<Map<String, Object>> ruleManager;
    private final WorkerCandidateRuntime candidateRuntime;
    private final WorkerAdmissionRuntime admissionRuntime;
    private final AssignmentDiagnosticRecorder recordService;
    private final TraceEventLogger traceEventLogger;
    private final WorkerCandidateRanker candidateRanker;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerSchedulingCandidateEnumerator candidateEnumerator;

    RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
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

    RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                        WorkerManager workerManager,
                                        AssignmentDiagnosticRecorder recordService,
                                        TraceEventLogger traceEventLogger,
                                        WorkerCandidateRanker candidateRanker) {
        this(ruleManager, workerManager, recordService, traceEventLogger, candidateRanker,
                new DefaultWorkerDispatchResourcePolicy());
    }

    RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                        WorkerManager workerManager,
                                        AssignmentDiagnosticRecorder recordService,
                                        TraceEventLogger traceEventLogger,
                                        WorkerCandidateRanker candidateRanker,
                                        WorkerDispatchResourcePolicy resourcePolicy) {
        this(ruleManager, workerManager, recordService, traceEventLogger, candidateRanker,
                resourcePolicy, null);
    }

    RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                        WorkerManager workerManager,
                                        AssignmentDiagnosticRecorder recordService,
                                        TraceEventLogger traceEventLogger,
                                        WorkerCandidateRanker candidateRanker,
                                        WorkerDispatchResourcePolicy resourcePolicy,
                                        WorkerSchedulingCandidateEnumerator candidateEnumerator) {
        this(ruleManager, workerManager, workerManager, recordService, traceEventLogger,
                candidateRanker, resourcePolicy, candidateEnumerator);
    }

    RuleBasedTaskWorkerMatchingStrategy(RuleManager<Map<String, Object>> ruleManager,
                                        WorkerCandidateRuntime candidateRuntime,
                                        WorkerAdmissionRuntime admissionRuntime,
                                        AssignmentDiagnosticRecorder recordService,
                                        TraceEventLogger traceEventLogger,
                                        WorkerCandidateRanker candidateRanker,
                                        WorkerDispatchResourcePolicy resourcePolicy,
                                        WorkerSchedulingCandidateEnumerator candidateEnumerator) {
        this.ruleManager = ruleManager;
        this.candidateRuntime = Objects.requireNonNull(candidateRuntime, "candidateRuntime");
        this.admissionRuntime = Objects.requireNonNull(admissionRuntime, "admissionRuntime");
        this.recordService = recordService;
        this.traceEventLogger = traceEventLogger;
        this.candidateRanker = candidateRanker != null ? candidateRanker : new DefaultWorkerCandidateRanker();
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        WorkerSchedulingViewRuntime schedulingViewRuntime = candidateRuntime instanceof WorkerSchedulingViewRuntime runtime
                ? runtime
                : requireSchedulingViewRuntime(candidateRuntime);
        this.candidateEnumerator = candidateEnumerator == null
                ? new WorkerSchedulingCandidateEnumerator(schedulingViewRuntime)
                : candidateEnumerator;
    }

    @Override
    public List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount) {
        List<WorkerSchedulingCandidate> matchedWorkers = new ArrayList<>();
        if (maxWorkerCount <= 0) {
            return matchedWorkers;
        }
        WorkerTaskSelector selector = WorkerTaskSelectorFactory.fromTask(task);
        WorkerCandidateBatch candidateBatch = candidateRuntime.findWorkerCandidateBatch(
                selector,
                candidateAcquisitionLimit(task, maxWorkerCount)
        );
        List<Worker> candidates = candidateBatch.candidates();
        List<RuleDefinition> rules = ruleManager.getDefaultRules();
        List<RulePassedCandidate> rulePassedCandidates = new ArrayList<>();

        log.info("[WorkerAssign] Matching workers for task {} (routingCode: {}, candidates: {}, "
                        + "warmCandidates: {}, coldCandidates: {}, warmRejected: {}, rules: {})",
                task.getTid(), TaskSharedConfig.routingCode(task), candidates.size(),
                candidateBatch.warmCandidateCount(), candidateBatch.coldCandidateCount(),
                candidateBatch.warmSourceGuardRejectedCount(), rules.size());

        if (log.isDebugEnabled()) {
            for (RuleDefinition rule : rules) {
                log.debug("[WorkerAssign] Rule: {} - {}", rule.getId(), rule.getContent());
            }
        }

        List<WorkerSchedulingCandidate> schedulingCandidates = candidateEnumerator.enumerate(candidates);
        Set<String> completedWorkerIds = new HashSet<>();

        for (WorkerSchedulingCandidate candidate : schedulingCandidates) {
            Worker worker = candidate.getWorker();
            if (completedWorkerIds.contains(worker.getWorkerId())) {
                continue;
            }

            PrefilterDecision prefilterDecision = prefilterCandidate(task, candidate);
            if (!prefilterDecision.passed()) {
                traceEventLogger.workerMatchRejected(task.getTid(), candidate, prefilterDecision.reason(),
                        null, null);
                recordService.recordWorkerAssignment(
                        task, candidate, prefilterDecision.result(),
                        prefilterDecision.reason(),
                        new ArrayList<>(), withCandidateSourceStats(prefilterDecision.contextSnapshot(), candidateBatch),
                        prefilterDecision.workerLocked()
                );
                log.debug("Worker candidate rejected before rule evaluation: {} ({})",
                        worker.getWorkerId(),
                        prefilterDecision.reason());
                continue;
            }

            WorkerMatchContext matchContext = new WorkerMatchContext(candidate, task);

            if (log.isDebugEnabled()) {
                log.debug("[Debug] WorkerId={}, workerGroupId={}, status={}, locked={}, supportedProjects={}",
                        worker.getWorkerId(),
                        worker.getWorkerGroupId(),
                        worker.getStatus(),
                        admissionRuntime.hasWorkerExclusiveLease(worker.getWorkerId()),
                        String.join(", ", candidate.getSchedulingView().supportedProjects())
                );
                log.debug("[Debug] WorkerMatchContext: {}", matchContext.getContext());
            }

            try {
                List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext, rules);
                long hitCount = ruleEvaluations.stream().filter(RuleEvaluationDetail::isPassed).count();

                log.debug("[WorkerAssign] Worker {} - Hit rules: {}/{}",
                        worker.getWorkerId(),
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
                traceEventLogger.workerMatchRejected(task.getTid(), candidate,
                        "rule evaluation failed: " + failedRules, null, null);
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.RULE_NOT_MATCH,
                        "rule evaluation failed: " + failedRules,
                        ruleEvaluations, withCandidateSourceStats(matchContext.getContext(), candidateBatch),
                        admissionRuntime.hasWorkerExclusiveLease(worker.getWorkerId())
                );
                log.debug("Rule not matched: {} (failed rules: {})",
                        worker.getWorkerId(),
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
                traceEventLogger.workerMatchRejected(task.getTid(), candidate,
                        "rule evaluation exception: " + e.getMessage(), null, null);
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.FAILED,
                        "rule evaluation exception: " + e.getMessage(),
                        new ArrayList<>(), withCandidateSourceStats(matchContext.getContext(), candidateBatch),
                        admissionRuntime.hasWorkerExclusiveLease(worker.getWorkerId())
                );
                log.error("Error evaluating rules for worker {}: {}",
                        worker.getWorkerId(),
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
            double candidateScore = rankScore(rankedContext, task);
            boolean exclusiveWorkerLock = resourcePolicy.usageForCandidate(task, candidate).exclusiveWorkerLock();
            if (!admissionRuntime.tryReserveWorkerCapacity(worker.getWorkerId(), task.getTid())) {
                traceEventLogger.workerMatchRejected(task, candidate,
                        "worker capacity unavailable after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.QUOTA_EXCEEDED,
                        "worker capacity unavailable after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        admissionRuntime.hasWorkerExclusiveLease(worker.getWorkerId())
                );
                log.debug("Worker capacity unavailable after candidate ranking: {}", worker.getWorkerId());
                continue;
            }
            if (!exclusiveWorkerLock) {
                traceEventLogger.workerMatchAccepted(task, candidate,
                        "all rules matched and worker capacity reserved after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.SUCCESS,
                        "all rules matched and worker capacity reserved after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        false
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched without exclusive lock: {} for background task {} at rank {}",
                        worker.getWorkerId(),
                        task.getTid(),
                        rank);
                continue;
            }
            if (admissionRuntime.tryAcquireWorkerExclusiveLease(worker.getWorkerId())) {
                traceEventLogger.workerLockAcquired(task.getTid(), worker.getWorkerId(),
                        "TRY_LOCK_WORKER", "RuleBasedTaskWorkerMatchingStrategy",
                        "all rules matched after candidate ranking");
                traceEventLogger.workerMatchAccepted(task, candidate,
                        "all rules matched and worker lock acquired after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.SUCCESS,
                        "all rules matched and worker lock acquired after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        true
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched: {} for task {} at rank {}",
                        worker.getWorkerId(),
                        task.getTid(),
                        rank);
            } else {
                admissionRuntime.releaseWorkerReservation(worker.getWorkerId(), task.getTid());
                traceEventLogger.workerMatchRejected(task, candidate,
                        "worker lock conflict after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.getWorkerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.CONFLICT,
                        "worker lock conflict after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        admissionRuntime.hasWorkerExclusiveLease(worker.getWorkerId())
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

    private int candidateAcquisitionLimit(Task task, int maxWorkerCount) {
        if (TaskSharedConfig.targetWorkerId(task) != null) {
            return 1;
        }
        int sampleMin = Math.max(1, DEFAULT_STAGE_ONE_SAMPLE_MIN);
        int sampleMax = Math.max(sampleMin, DEFAULT_STAGE_ONE_SAMPLE_MAX);
        int oversampleFactor = Math.max(1, DEFAULT_STAGE_ONE_OVERSAMPLE_FACTOR);
        long desiredSample = Math.max(1L, (long) maxWorkerCount * oversampleFactor);
        return (int) Math.min(sampleMax, Math.max(sampleMin, desiredSample));
    }

    private Map<String, Object> withCandidateSourceStats(Map<String, Object> context,
                                                         WorkerCandidateBatch candidateBatch) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (context != null) {
            snapshot.putAll(context);
        }
        if (candidateBatch != null) {
            snapshot.put("workerCandidateWarmCount", candidateBatch.warmCandidateCount());
            snapshot.put("workerCandidateColdCount", candidateBatch.coldCandidateCount());
            snapshot.put("workerCandidateWarmRejectedCount", candidateBatch.warmSourceGuardRejectedCount());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static WorkerSchedulingViewRuntime requireSchedulingViewRuntime(WorkerCandidateRuntime candidateRuntime) {
        if (candidateRuntime instanceof WorkerSchedulingViewRuntime runtime) {
            return runtime;
        }
        throw new IllegalArgumentException("candidateRuntime must also implement WorkerSchedulingViewRuntime");
    }

    private PrefilterDecision prefilterCandidate(Task task, WorkerSchedulingCandidate candidate) {
        Worker worker = candidate.getWorker();
        WorkerSchedulingView schedulingView = candidate.getSchedulingView();
        WorkerReachabilityState reachability = schedulingView.reachability();
        Map<String, Object> contextSnapshot = WorkerMatchContext.contextSnapshot(candidate, task);
        if (!schedulingView.dispatchEnabled()) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker unavailable", contextSnapshot, false);
        }
        if (reachability != WorkerReachabilityState.ONLINE) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker transport unreachable", contextSnapshot, false);
        }
        boolean workerLocked = schedulingView.workerLocked();
        if (workerLocked) {
            return PrefilterDecision.reject(AssignmentResult.CONFLICT,
                    "worker locked", contextSnapshot, true);
        }
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        if (targetWorkerId != null && !targetWorkerId.equals(schedulingView.workerId())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker mismatch", contextSnapshot, false);
        }
        if (!targetWorkerAttributes.isEmpty()
                && Boolean.FALSE.equals(contextSnapshot.get("matchesTargetWorkerAttributes"))) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker attributes mismatch", contextSnapshot, false);
        }

        String routingCode = TaskSharedConfig.routingCode(task);
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        if (taskHasRoutingRequirement && !schedulingView.schedulingRoutingTagsContain(routingCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "routing code mismatch", contextSnapshot, false);
        }
        return PrefilterDecision.allow();
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
