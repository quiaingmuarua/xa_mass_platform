-- Replace the file glob and task id with your local values before running.
SELECT
    epoch_ms(ts) AS time,
    eventType,
    traceId,
    identity.taskId,
    identity.messageId,
    identity.attemptId,
    transition.src,
    transition.dst,
    outcome.success,
    outcome.errorCode,
    attrs.reason,
    attrs.source
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE identity.taskId = 't-xxx'
ORDER BY ts;
