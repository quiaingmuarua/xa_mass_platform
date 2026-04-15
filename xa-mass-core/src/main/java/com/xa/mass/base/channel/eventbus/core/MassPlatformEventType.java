package com.xa.mass.base.channel.eventbus.core;


/**
 * 平台治理/监控事件类型
 */
public enum MassPlatformEventType {
    // 任务审核相关
    TASK_REVIEW_RANDOM("任务随机审核"),
    TASK_REVIEW_DELAY("任务延迟审核"),
    TASK_REVIEW_PARTIAL_FAILURE("任务部分失败"),
    TASK_REVIEW_MISAPPROVAL("任务误通过"),

    // 任务生命周期相关
    TASK_CREATED("任务创建"),
    TASK_AUDITED("任务审核通过"),
    TASK_ASSIGNED("任务分配"),

    // Worker 状态相关
    WORKER_OFFLINE_BATCH("Worker批量下线"),
    WORKER_OFFLINE_SINGLE("Worker单个下线"),
    WORKER_FLASH_DISCONNECT("Worker闪断"),
    WORKER_LONG_ABSENCE("Worker长时间不归队"),
    WORKER_ONLINE_BATCH("Worker批量上线"),

    // WorkerContext/消息异常
    WORKER_CONTEXT_INVALIDATION("WorkerContext失效"),
    WORKER_CONTEXT_RETRY_LOOP("WorkerContext反复重试"),
    WORKER_CONTEXT_BATCH_UNAVAILABLE("WorkerContext批量不可用"),
    MESSAGE_PROCESSING_ERROR("消息处理异常"),

    // 任务分配冲突
    TASK_ASSIGNMENT_CONFLICT("任务分配冲突"),
    MESSAGE_DUPLICATE_ASSIGNMENT("消息重复分配"),
    BATCH_ORDER_CHAOS("批次乱序"),

    // 网络/链路异常
    RPC_TIMEOUT("RPC超时"),
    MESSAGE_QUEUE_BLOCK("消息队列堵塞"),
    NETWORK_LATENCY("网络延迟"),

    // 监控/归因失效
    LOGGING_FAILURE("日志打点异常"),
    STATUS_REPORT_FAILURE("状态回报异常"),
    METRICS_COLLECTION_FAILURE("指标收集异常");

    private final String description;

    MassPlatformEventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
