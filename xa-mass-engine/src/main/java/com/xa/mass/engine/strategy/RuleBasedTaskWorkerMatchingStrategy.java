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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        List<Worker> candidates = workerManager.getAllWorkers();
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
}
