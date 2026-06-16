package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolution;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateBatch;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.engine.model.RuleEvaluationDetail;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.rules.MatchingRuleEvaluator;
import com.xa.mass.engine.rules.MatchingRuleSetProvider;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionStatus;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
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
    private static final String REJECTION_OWNER_STAGE2_POLICY = "STAGE2_POLICY";
    private static final String REJECTION_OWNER_RESERVE = "RESERVE";

    private final MatchingRuleSetProvider ruleSetProvider;
    private final MatchingRuleEvaluator<Map<String, Object>> ruleEvaluator;
    private final WorkerCandidateRuntime candidateRuntime;
    private final WorkerAdmissionRuntime admissionRuntime;
    private final AssignmentDiagnosticRecorder recordService;
    private final TraceEventLogger traceEventLogger;
    private final WorkerCandidateRanker candidateRanker;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerSchedulingCandidateEnumerator candidateEnumerator;
    private final SchedulingPlaneResolver schedulingPlaneResolver;

    public RuleBasedTaskWorkerMatchingStrategy(MatchingRuleSetProvider ruleSetProvider,
                                               MatchingRuleEvaluator<Map<String, Object>> ruleEvaluator,
                                               WorkerCandidateRuntime candidateRuntime,
                                               WorkerAdmissionRuntime admissionRuntime,
                                               WorkerSchedulingViewRuntime schedulingViewRuntime,
                                               AssignmentDiagnosticRecorder recordService,
                                               TraceEventLogger traceEventLogger) {
        this(ruleSetProvider, ruleEvaluator, candidateRuntime, admissionRuntime, schedulingViewRuntime, recordService,
                traceEventLogger, new DefaultSchedulingPlaneResolver(),
                new DefaultWorkerCandidateRanker(), new DefaultWorkerDispatchResourcePolicy(), null);
    }

    RuleBasedTaskWorkerMatchingStrategy(MatchingRuleSetProvider ruleSetProvider,
                                        MatchingRuleEvaluator<Map<String, Object>> ruleEvaluator,
                                        WorkerCandidateRuntime candidateRuntime,
                                        WorkerAdmissionRuntime admissionRuntime,
                                        WorkerSchedulingViewRuntime schedulingViewRuntime,
                                        AssignmentDiagnosticRecorder recordService,
                                        TraceEventLogger traceEventLogger,
                                        WorkerCandidateRanker candidateRanker,
                                        WorkerDispatchResourcePolicy resourcePolicy,
                                        WorkerSchedulingCandidateEnumerator candidateEnumerator) {
        this(ruleSetProvider, ruleEvaluator, candidateRuntime, admissionRuntime, schedulingViewRuntime, recordService,
                traceEventLogger, new DefaultSchedulingPlaneResolver(), candidateRanker, resourcePolicy,
                candidateEnumerator);
    }

    public RuleBasedTaskWorkerMatchingStrategy(MatchingRuleSetProvider ruleSetProvider,
                                               MatchingRuleEvaluator<Map<String, Object>> ruleEvaluator,
                                               WorkerCandidateRuntime candidateRuntime,
                                               WorkerAdmissionRuntime admissionRuntime,
                                               WorkerSchedulingViewRuntime schedulingViewRuntime,
                                               AssignmentDiagnosticRecorder recordService,
                                               TraceEventLogger traceEventLogger,
                                               SchedulingPlaneResolver schedulingPlaneResolver,
                                               WorkerCandidateRanker candidateRanker,
                                               WorkerDispatchResourcePolicy resourcePolicy,
                                               WorkerSchedulingCandidateEnumerator candidateEnumerator) {
        this.ruleSetProvider = Objects.requireNonNull(ruleSetProvider, "ruleSetProvider");
        this.ruleEvaluator = Objects.requireNonNull(ruleEvaluator, "ruleEvaluator");
        this.candidateRuntime = Objects.requireNonNull(candidateRuntime, "candidateRuntime");
        this.admissionRuntime = Objects.requireNonNull(admissionRuntime, "admissionRuntime");
        this.recordService = recordService;
        this.traceEventLogger = traceEventLogger;
        SchedulingPlaneResolver resolvedSchedulingPlaneResolver = schedulingPlaneResolver == null
                ? new DefaultSchedulingPlaneResolver()
                : schedulingPlaneResolver;
        this.schedulingPlaneResolver = resolvedSchedulingPlaneResolver;
        this.candidateRanker = candidateRanker != null ? candidateRanker : new DefaultWorkerCandidateRanker();
        this.resourcePolicy = resourcePolicy == null
                ? new DefaultWorkerDispatchResourcePolicy(resolvedSchedulingPlaneResolver)
                : resourcePolicy;
        WorkerSchedulingViewRuntime schedulingRuntime =
                Objects.requireNonNull(schedulingViewRuntime, "schedulingViewRuntime");
        this.candidateEnumerator = candidateEnumerator == null
                ? new WorkerSchedulingCandidateEnumerator(schedulingRuntime)
                : candidateEnumerator;
    }

    @Override
    public List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount) {
        List<WorkerSchedulingCandidate> matchedWorkers = new ArrayList<>();
        if (maxWorkerCount <= 0) {
            return matchedWorkers;
        }
        SchedulingPlaneResolution resolution = schedulingPlaneResolver.resolve(task);
        TaskDispatchIntent dispatchIntent = resolution.dispatchIntent();
        ResolvedTaskSchedulingPolicy taskSchedulingPolicy = resolution.taskSchedulingPolicy();
        WorkerTaskSelector selector = WorkerTaskSelectorFactory.fromPolicy(resolution.workerSchedulingPolicy());
        WorkerCandidateBatch<WorkerCandidateRow> candidateBatch = candidateRuntime.findWorkerCandidateBatch(
                selector,
                candidateAcquisitionLimit(dispatchIntent, maxWorkerCount)
        );
        List<WorkerCandidateRow> candidates = candidateBatch.candidates();
        List<RuleDefinition> rules = ruleSetProvider.activeWorkerMatchingRules();
        List<RulePassedCandidate> rulePassedCandidates = new ArrayList<>();

        log.info("[WorkerAssign] Matching workers for task {} (routingCode: {}, candidates: {}, "
                        + "warmCandidates: {}, coldCandidates: {}, warmRejected: {}, rules: {})",
                task.getTid(), dispatchIntent.routingCode(), candidates.size(),
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
            WorkerCandidateRow worker = candidate.getCandidateRow();
            if (completedWorkerIds.contains(worker.workerId())) {
                continue;
            }

            PrefilterDecision prefilterDecision = prefilterCandidate(task, dispatchIntent, taskSchedulingPolicy, candidate);
            if (!prefilterDecision.passed()) {
                traceEventLogger.workerMatchRejected(task.getTid(), candidate, prefilterDecision.reason(),
                        null, null);
                recordService.recordWorkerAssignment(
                        task, candidate, prefilterDecision.result(),
                        prefilterDecision.reason(),
                        new ArrayList<>(), withCandidateSourceStats(
                                prefilterDecision.contextSnapshot(), candidateBatch, prefilterDecision.rejectionOwner()),
                        prefilterDecision.workerLocked()
                );
                log.debug("Worker candidate rejected before rule evaluation: {} ({})",
                        worker.workerId(),
                        prefilterDecision.reason());
                continue;
            }

            WorkerMatchContext matchContext =
                    new WorkerMatchContext(candidate, task, dispatchIntent, taskSchedulingPolicy);

            if (log.isDebugEnabled()) {
                log.debug("[Debug] WorkerId={}, workerGroupId={}, readiness={}, locked={}, supportedProjects={}",
                        worker.workerId(),
                        worker.workerGroupId(),
                        candidate.getSchedulingView().readinessState(),
                        admissionRuntime.hasWorkerExclusiveLease(worker.workerId()),
                        String.join(", ", candidate.getSchedulingView().supportedProjects())
                );
                log.debug("[Debug] WorkerMatchContext: {}", matchContext.getContext());
            }

            try {
                List<RuleEvaluationDetail> ruleEvaluations = evaluateRulesWithDetails(matchContext, rules);
                long hitCount = ruleEvaluations.stream().filter(RuleEvaluationDetail::isPassed).count();

                log.debug("[WorkerAssign] Worker {} - Hit rules: {}/{}",
                        worker.workerId(),
                        hitCount,
                        rules.size());

                if (hitCount == rules.size()) {
                    rulePassedCandidates.add(new RulePassedCandidate(candidate, matchContext, ruleEvaluations));
                    completedWorkerIds.add(worker.workerId());
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
                        ruleEvaluations, withCandidateSourceStats(
                                matchContext.getContext(), candidateBatch, REJECTION_OWNER_STAGE2_POLICY),
                        admissionRuntime.hasWorkerExclusiveLease(worker.workerId())
                );
                log.debug("Rule not matched: {} (failed rules: {})",
                        worker.workerId(),
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
                        new ArrayList<>(), withCandidateSourceStats(
                                matchContext.getContext(), candidateBatch, REJECTION_OWNER_STAGE2_POLICY),
                        admissionRuntime.hasWorkerExclusiveLease(worker.workerId())
                );
                log.error("Error evaluating rules for worker {}: {}",
                        worker.workerId(),
                        e.getMessage());
            }
        }

        Map<WorkerMatchContext, RulePassedCandidate> passedByContext = new IdentityHashMap<>();
        List<WorkerMatchContext> contextsToRank = new ArrayList<>();
        for (RulePassedCandidate passedCandidate : rulePassedCandidates) {
            contextsToRank.add(passedCandidate.matchContext());
            passedByContext.put(passedCandidate.matchContext(), passedCandidate);
        }
        List<WorkerMatchContext> rankedContexts = candidateRanker.rank(contextsToRank, dispatchIntent);
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
            WorkerCandidateRow worker = candidate.getCandidateRow();
            double candidateScore = rankScore(rankedContext, dispatchIntent);
            boolean exclusiveWorkerLock = resourcePolicy.usageForCandidate(task, candidate).exclusiveWorkerLock();
            WorkerAdmissionTarget admissionTarget = WorkerAdmissionTarget.groupScoped(
                    worker.workerGroupId(),
                    worker.workerId(),
                    task.getTid()
            );
            WorkerAdmissionResult reserveResult = admissionRuntime.reserveWorkerCapacity(admissionTarget);
            if (!reserveResult.accepted()) {
                String reserveRejectionReason = reserveRejectionReason(reserveResult);
                traceEventLogger.workerMatchRejected(task, candidate,
                        reserveRejectionReason, rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.workerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, reserveAssignmentResult(reserveResult),
                        reserveRejectionReason,
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(
                                rankedContext.getContext(), candidateBatch, REJECTION_OWNER_RESERVE),
                        admissionRuntime.hasWorkerExclusiveLease(worker.workerId())
                );
                log.debug("Worker reserve rejected after candidate ranking: worker={}, status={}, reason={}",
                        worker.workerId(), reserveResult.status(), reserveResult.reason());
                continue;
            }
            if (!exclusiveWorkerLock) {
                traceEventLogger.workerMatchAccepted(task, candidate,
                        "all rules matched and worker capacity reserved after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.workerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.SUCCESS,
                        "all rules matched and worker capacity reserved after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        false
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched without exclusive lock: {} for background task {} at rank {}",
                        worker.workerId(),
                        task.getTid(),
                        rank);
                continue;
            }
            if (admissionRuntime.tryAcquireWorkerExclusiveLease(worker.workerId())) {
                traceEventLogger.workerLockAcquired(task.getTid(), worker.workerId(),
                        "TRY_LOCK_WORKER", "RuleBasedTaskWorkerMatchingStrategy",
                        "all rules matched after candidate ranking");
                traceEventLogger.workerMatchAccepted(task, candidate,
                        "all rules matched and worker lock acquired after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.workerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.SUCCESS,
                        "all rules matched and worker lock acquired after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(rankedContext.getContext(), candidateBatch),
                        true
                );
                matchedWorkers.add(candidate);
                log.info("Worker matched: {} for task {} at rank {}",
                        worker.workerId(),
                        task.getTid(),
                        rank);
            } else {
                admissionRuntime.releaseWorkerReservation(admissionTarget);
                traceEventLogger.workerMatchRejected(task, candidate,
                        "worker lock conflict after candidate ranking", rank, candidateScore,
                        admissionRuntime.getWorkerLoad(worker.workerId()));
                recordService.recordWorkerAssignment(
                        task, candidate, AssignmentResult.CONFLICT,
                        "worker lock conflict after candidate ranking",
                        passedCandidate.ruleEvaluations(), withCandidateSourceStats(
                                rankedContext.getContext(), candidateBatch, REJECTION_OWNER_RESERVE),
                        admissionRuntime.hasWorkerExclusiveLease(worker.workerId())
                );
                log.debug("Worker locked after candidate ranking: {}", worker.workerId());
            }
        }

        log.info("[WorkerAssign] Total matched worker scheduling candidates: {} for task {}",
                matchedWorkers.size(), task.getTid());
        return matchedWorkers;
    }

    private double rankScore(WorkerMatchContext context, TaskDispatchIntent dispatchIntent) {
        if (candidateRanker instanceof DefaultWorkerCandidateRanker defaultRanker) {
            return defaultRanker.score(context, dispatchIntent);
        }
        return Double.NaN;
    }

    private static String reserveRejectionReason(WorkerAdmissionResult result) {
        if (result == null) {
            return "worker reserve rejected after candidate ranking";
        }
        if (result.status() == WorkerAdmissionStatus.CAPACITY_UNAVAILABLE) {
            return "worker capacity unavailable after candidate ranking";
        }
        if (result.reason() != null) {
            return "worker reserve rejected after candidate ranking: " + result.reason();
        }
        return "worker reserve rejected after candidate ranking: " + result.status().name();
    }

    private static AssignmentResult reserveAssignmentResult(WorkerAdmissionResult result) {
        if (result == null) {
            return AssignmentResult.QUOTA_EXCEEDED;
        }
        return switch (result.status()) {
            case DISPATCH_DISABLED, MISSING_SLOT, REMOVING_SLOT, STALE_HEARTBEAT -> AssignmentResult.RESOURCE_UNAVAILABLE;
            case GROUP_MISMATCH -> AssignmentResult.RULE_NOT_MATCH;
            default -> AssignmentResult.QUOTA_EXCEEDED;
        };
    }

    private int candidateAcquisitionLimit(TaskDispatchIntent dispatchIntent, int maxWorkerCount) {
        if (dispatchIntent != null && dispatchIntent.targetWorkerId() != null) {
            return 1;
        }
        int sampleMin = Math.max(1, DEFAULT_STAGE_ONE_SAMPLE_MIN);
        int sampleMax = Math.max(sampleMin, DEFAULT_STAGE_ONE_SAMPLE_MAX);
        int oversampleFactor = Math.max(1, DEFAULT_STAGE_ONE_OVERSAMPLE_FACTOR);
        long desiredSample = Math.max(1L, (long) maxWorkerCount * oversampleFactor);
        return (int) Math.min(sampleMax, Math.max(sampleMin, desiredSample));
    }

    private Map<String, Object> withCandidateSourceStats(Map<String, Object> context,
                                                         WorkerCandidateBatch<WorkerCandidateRow> candidateBatch) {
        return withCandidateSourceStats(context, candidateBatch, null);
    }

    private Map<String, Object> withCandidateSourceStats(Map<String, Object> context,
                                                         WorkerCandidateBatch<WorkerCandidateRow> candidateBatch,
                                                         String rejectionOwner) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (context != null) {
            snapshot.putAll(context);
        }
        if (candidateBatch != null) {
            snapshot.put("workerCandidateWarmCount", candidateBatch.warmCandidateCount());
            snapshot.put("workerCandidateColdCount", candidateBatch.coldCandidateCount());
            snapshot.put("workerCandidateWarmRejectedCount", candidateBatch.warmSourceGuardRejectedCount());
            snapshot.put("workerCandidateDuplicateCount", candidateBatch.duplicateCandidateCount());
        }
        if (rejectionOwner != null && !rejectionOwner.isBlank()) {
            snapshot.put("workerAssignmentRejectionOwner", rejectionOwner);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private PrefilterDecision prefilterCandidate(Task task,
                                                TaskDispatchIntent dispatchIntent,
                                                ResolvedTaskSchedulingPolicy taskSchedulingPolicy,
                                                WorkerSchedulingCandidate candidate) {
        WorkerSchedulingView schedulingView = candidate.getSchedulingView();
        WorkerReachabilityState reachability = schedulingView.reachability();
        Map<String, Object> contextSnapshot = WorkerMatchContext.contextSnapshot(
                candidate,
                task,
                dispatchIntent,
                taskSchedulingPolicy
        );
        if (reachability != WorkerReachabilityState.ONLINE) {
            return PrefilterDecision.reject(AssignmentResult.RESOURCE_UNAVAILABLE,
                    "worker transport unreachable", contextSnapshot, false, REJECTION_OWNER_STAGE2_POLICY);
        }
        boolean workerLocked = schedulingView.workerLocked();
        if (workerLocked) {
            return PrefilterDecision.reject(AssignmentResult.CONFLICT,
                    "worker locked", contextSnapshot, true, REJECTION_OWNER_RESERVE);
        }
        String targetWorkerId = dispatchIntent == null ? null : dispatchIntent.targetWorkerId();
        Map<String, String> targetWorkerAttributes = dispatchIntent == null
                ? Map.of()
                : dispatchIntent.targetWorkerAttributes();
        if (targetWorkerId != null && !targetWorkerId.equals(schedulingView.workerId())) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker mismatch", contextSnapshot, false, REJECTION_OWNER_STAGE2_POLICY);
        }
        if (!targetWorkerAttributes.isEmpty()
                && Boolean.FALSE.equals(contextSnapshot.get("matchesTargetWorkerAttributes"))) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "target worker attributes mismatch", contextSnapshot, false, REJECTION_OWNER_STAGE2_POLICY);
        }

        String routingCode = dispatchIntent == null ? null : dispatchIntent.routingCode();
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        if (taskHasRoutingRequirement && !schedulingView.schedulingRoutingTagsContain(routingCode)) {
            return PrefilterDecision.reject(AssignmentResult.RULE_NOT_MATCH,
                    "routing code mismatch", contextSnapshot, false, REJECTION_OWNER_STAGE2_POLICY);
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
                passed = ruleEvaluator.evaluate(rule, matchContext.getRuleContext());
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
        private final String rejectionOwner;

        private PrefilterDecision(boolean passed,
                                  AssignmentResult result,
                                  String reason,
                                  Map<String, Object> contextSnapshot,
                                  boolean workerLocked,
                                  String rejectionOwner) {
            this.passed = passed;
            this.result = result;
            this.reason = reason;
            this.contextSnapshot = contextSnapshot;
            this.workerLocked = workerLocked;
            this.rejectionOwner = rejectionOwner;
        }

        private static PrefilterDecision allow() {
            return new PrefilterDecision(true, null, null, Map.of(), false, null);
        }

        private static PrefilterDecision reject(AssignmentResult result,
                                                String reason,
                                                Map<String, Object> contextSnapshot,
                                                boolean workerLocked,
                                                String rejectionOwner) {
            return new PrefilterDecision(false, result, reason, contextSnapshot, workerLocked, rejectionOwner);
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

        private String rejectionOwner() {
            return rejectionOwner;
        }
    }
}
