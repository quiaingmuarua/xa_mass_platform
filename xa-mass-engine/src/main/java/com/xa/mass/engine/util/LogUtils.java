package com.xa.mass.engine.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 日志工具类，提供结构化日志记录功能
 * 支持MDC字段设置，便于SIGOZ等日志分析工具进行日志分析
 */
public class LogUtils {

    // MDC字段名常量
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String WORKER_ID = "workerId";
    public static final String TASK_ID = "taskId";
    public static final String WORKER_CONTEXT_ID = "workerContextId";
    public static final String OPERATION = "operation";
    public static final String MODULE = "module";
    public static final String RESULT = "result";
    public static final String DURATION = "duration";
    public static final String ERROR_CODE = "errorCode";
    private static final Logger logger = LoggerFactory.getLogger(LogUtils.class);

    /**
     * 生成跟踪ID
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置跟踪ID
     */
    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    /**
     * 设置用户ID
     */
    public static void setUserId(String userId) {
        MDC.put(USER_ID, userId);
    }

    /**
     * 设置Worker ID
     */
    public static void setWorkerId(String workerId) {
        MDC.put(WORKER_ID, workerId);
    }

    /**
     * 设置任务ID
     */
    public static void setTaskId(String taskId) {
        MDC.put(TASK_ID, taskId);
    }

    /**
     * 设置WorkerContext ID
     */
    public static void setWorkerContextId(String workerContextId) {
        MDC.put(WORKER_CONTEXT_ID, workerContextId);
    }

    /**
     * 设置操作类型
     */
    public static void setOperation(String operation) {
        MDC.put(OPERATION, operation);
    }

    /**
     * 设置模块名
     */
    public static void setModule(String module) {
        MDC.put(MODULE, module);
    }

    /**
     * 设置操作结果
     */
    public static void setResult(String result) {
        MDC.put(RESULT, result);
    }

    /**
     * 设置执行时长（毫秒）
     */
    public static void setDuration(long duration) {
        MDC.put(DURATION, String.valueOf(duration));
    }

    /**
     * 设置错误代码
     */
    public static void setErrorCode(String errorCode) {
        MDC.put(ERROR_CODE, errorCode);
    }

    /**
     * 清除所有MDC字段
     */
    public static void clearMdc() {
        MDC.clear();
    }

    /**
     * 清除指定MDC字段
     */
    public static void removeMdc(String key) {
        MDC.remove(key);
    }

    /**
     * 记录业务操作开始日志
     */
    public static void logOperationStart(String operation, String module, String... params) {
        setOperation(operation);
        setModule(module);
        setTraceId(generateTraceId());

        StringBuilder message = new StringBuilder("操作开始: ").append(operation);
        if (params.length > 0) {
            message.append(" - 参数: ");
            for (int i = 0; i < params.length; i += 2) {
                if (i + 1 < params.length) {
                    message.append(params[i]).append("=").append(params[i + 1]).append(", ");
                }
            }
            message.setLength(message.length() - 2); // 移除最后的逗号和空格
        }

        logger.info(message.toString());
    }

    /**
     * 记录业务操作成功日志
     */
    public static void logOperationSuccess(String result, long duration) {
        setResult("SUCCESS");
        setDuration(duration);
        logger.info("操作成功: 结果={}, 耗时={}ms", result, duration);
    }

    /**
     * 记录业务操作失败日志
     */
    public static void logOperationFailure(String errorCode, String errorMessage, long duration) {
        setResult("FAILURE");
        setErrorCode(errorCode);
        setDuration(duration);
        logger.error("操作失败: 错误代码={}, 错误信息={}, 耗时={}ms", errorCode, errorMessage, duration);
    }

    /**
     * 记录Worker相关日志
     */
    public static void logWorkerOperation(String workerId, String operation, String result) {
        setWorkerId(workerId);
        setOperation(operation);
        setResult(result);
        logger.info("Worker操作: WorkerID={}, 操作={}, 结果={}", workerId, operation, result);
    }

    /**
     * 记录任务相关日志
     */
    public static void logTaskOperation(String taskId, String operation, String result) {
        setTaskId(taskId);
        setOperation(operation);
        setResult(result);
        logger.info("任务操作: 任务ID={}, 操作={}, 结果={}", taskId, operation, result);
    }

    /**
     * 记录WorkerContext相关日志
     */
    public static void logWorkerContextOperation(String workerContextId, String operation, String result) {
        setWorkerContextId(workerContextId);
        setOperation(operation);
        setResult(result);
        logger.info("WorkerContext操作: WorkerContextID={}, 操作={}, 结果={}", workerContextId, operation, result);
    }

    /**
     * 记录规则评估日志
     */
    public static void logRuleEvaluation(String ruleId, String workerId, String taskId, boolean passed) {
        setWorkerId(workerId);
        setTaskId(taskId);
        setOperation("RULE_EVALUATION");
        setResult(passed ? "PASSED" : "FAILED");
        logger.info("规则评估: 规则ID={}, WorkerID={}, 任务ID={}, 结果={}",
                ruleId, workerId, taskId, passed ? "通过" : "不通过");
    }

    /**
     * 记录任务分配日志
     */
    public static void logTaskAssignment(String taskId, String workerId, String result) {
        setTaskId(taskId);
        setWorkerId(workerId);
        setOperation("TASK_ASSIGNMENT");
        setResult(result);
        logger.info("任务分配: 任务ID={}, WorkerID={}, 结果={}", taskId, workerId, result);
    }
}
