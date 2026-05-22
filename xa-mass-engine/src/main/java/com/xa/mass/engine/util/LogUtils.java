package com.xa.mass.engine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.*;

/**
 * Small structured logging helper for engine/runtime code.
 *
 * <p>This class only manages MDC fields and a few consistent log templates.
 * Detailed lifecycle trace belongs in {@link TraceEventLogger}.
 */
public final class LogUtils {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String WORKER_ID = "workerId";
    public static final String TASK_ID = "taskId";
    public static final String OPERATION = "operation";
    public static final String MODULE = "module";
    public static final String RESULT = "result";
    public static final String DURATION = "duration";
    public static final String ERROR_CODE = "errorCode";

    private static final Logger logger = LoggerFactory.getLogger(LogUtils.class);
    private static final ThreadLocal<Deque<Map<String, String>>> OPERATION_CONTEXT_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LogUtils() {
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void setUserId(String userId) {
        MDC.put(USER_ID, userId);
    }

    public static void setWorkerId(String workerId) {
        MDC.put(WORKER_ID, workerId);
    }

    public static void setTaskId(String taskId) {
        MDC.put(TASK_ID, taskId);
    }

    public static void setOperation(String operation) {
        MDC.put(OPERATION, operation);
    }

    public static void setModule(String module) {
        MDC.put(MODULE, module);
    }

    public static void setResult(String result) {
        MDC.put(RESULT, result);
    }

    public static void setDuration(long duration) {
        MDC.put(DURATION, String.valueOf(duration));
    }

    public static void setErrorCode(String errorCode) {
        MDC.put(ERROR_CODE, errorCode);
    }

    public static void clearMdc() {
        MDC.clear();
        OPERATION_CONTEXT_STACK.remove();
    }

    public static void removeMdc(String key) {
        MDC.remove(key);
    }

    public static void logOperationStart(String operation, String module, String... params) {
        pushOperationContext();
        setOperation(operation);
        setModule(module);
        setTraceId(generateTraceId());
        removeMdc(RESULT);
        removeMdc(DURATION);
        removeMdc(ERROR_CODE);

        StringBuilder message = new StringBuilder("Operation started: ").append(operation);
        if (params.length > 0) {
            message.append(" | params: ");
            for (int i = 0; i < params.length; i += 2) {
                if (i + 1 < params.length) {
                    message.append(params[i]).append("=").append(params[i + 1]).append(", ");
                }
            }
            message.setLength(message.length() - 2);
        }

        logger.info(message.toString());
    }

    public static void logOperationSuccess(String result, long duration) {
        setResult("SUCCESS");
        setDuration(duration);
        logger.info("Operation succeeded: result={}, durationMs={}", result, duration);
        restoreOperationContext();
    }

    public static void logOperationFailure(String errorCode, String errorMessage, long duration) {
        setResult("FAILURE");
        setErrorCode(errorCode);
        setDuration(duration);
        logger.error("Operation failed: errorCode={}, errorMessage={}, durationMs={}",
                errorCode, errorMessage, duration);
        restoreOperationContext();
    }

    public static void logWorkerOperation(String workerId, String operation, String result) {
        setWorkerId(workerId);
        setOperation(operation);
        setResult(result);
        logger.info("Worker operation: workerId={}, operation={}, result={}", workerId, operation, result);
    }

    public static void logTaskOperation(String taskId, String operation, String result) {
        setTaskId(taskId);
        setOperation(operation);
        setResult(result);
        logger.info("Task operation: taskId={}, operation={}, result={}", taskId, operation, result);
    }

    public static void logRuleEvaluation(String ruleId, String workerId, String taskId, boolean passed) {
        setWorkerId(workerId);
        setTaskId(taskId);
        setOperation("RULE_EVALUATION");
        setResult(passed ? "PASSED" : "FAILED");
        logger.info("Rule evaluation: ruleId={}, workerId={}, taskId={}, result={}",
                ruleId, workerId, taskId, passed ? "PASSED" : "FAILED");
    }

    public static void logTaskAssignment(String taskId, String workerId, String result) {
        setTaskId(taskId);
        setWorkerId(workerId);
        setOperation("TASK_ASSIGNMENT");
        setResult(result);
        logger.info("Task assignment: taskId={}, workerId={}, result={}", taskId, workerId, result);
    }

    private static void pushOperationContext() {
        OPERATION_CONTEXT_STACK.get().push(captureOperationContext());
    }

    private static Map<String, String> captureOperationContext() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(TRACE_ID, MDC.get(TRACE_ID));
        snapshot.put(OPERATION, MDC.get(OPERATION));
        snapshot.put(MODULE, MDC.get(MODULE));
        snapshot.put(RESULT, MDC.get(RESULT));
        snapshot.put(DURATION, MDC.get(DURATION));
        snapshot.put(ERROR_CODE, MDC.get(ERROR_CODE));
        return snapshot;
    }

    private static void restoreOperationContext() {
        Deque<Map<String, String>> stack = OPERATION_CONTEXT_STACK.get();
        if (stack.isEmpty()) {
            removeMdc(TRACE_ID);
            removeMdc(OPERATION);
            removeMdc(MODULE);
            removeMdc(RESULT);
            removeMdc(DURATION);
            removeMdc(ERROR_CODE);
            OPERATION_CONTEXT_STACK.remove();
            return;
        }

        Map<String, String> previous = stack.pop();
        restoreManagedKey(TRACE_ID, previous.get(TRACE_ID));
        restoreManagedKey(OPERATION, previous.get(OPERATION));
        restoreManagedKey(MODULE, previous.get(MODULE));
        restoreManagedKey(RESULT, previous.get(RESULT));
        restoreManagedKey(DURATION, previous.get(DURATION));
        restoreManagedKey(ERROR_CODE, previous.get(ERROR_CODE));

        if (stack.isEmpty()) {
            OPERATION_CONTEXT_STACK.remove();
        }
    }

    private static void restoreManagedKey(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
