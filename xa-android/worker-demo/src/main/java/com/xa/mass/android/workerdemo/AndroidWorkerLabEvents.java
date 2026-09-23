package com.xa.mass.android.workerdemo;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed fault capabilities owned by the Android demo host. */
final class AndroidWorkerLabEvents {

    static final String DELAY_EVENT = "extension.worker.lab.delay";
    static final String FAIL_EVENT = "extension.worker.lab.fail";

    private static final String DELAY_CAPABILITY = "lab.delay";
    private static final String FAIL_CAPABILITY = "lab.fail";
    private static final long MAX_DELAY_MILLIS = 30_000L;
    private static final Set<String> DELAY_FIELDS = Set.of("delayMillis");

    private final AtomicInteger activeDelayCount = new AtomicInteger();

    List<WorkerEventDefinition<?>> definitions() {
        return List.of(
                WorkerEventDefinition.extension(
                        DELAY_CAPABILITY,
                        WorkerEventParameterResolvers.jsonMap(),
                        this::delay
                ),
                WorkerEventDefinition.extension(
                        FAIL_CAPABILITY,
                        WorkerEventParameterResolvers.jsonMap(),
                        AndroidWorkerLabEvents::fail
                )
        );
    }

    int activeDelayCount() {
        return activeDelayCount.get();
    }

    private String delay(Map<String, Object> payload) {
        Object rawDelayMillis = payload.get("delayMillis");
        if (!payload.keySet().equals(DELAY_FIELDS)
                || !(rawDelayMillis instanceof Long)
                || ((Long) rawDelayMillis) < 1L
                || ((Long) rawDelayMillis) > MAX_DELAY_MILLIS) {
            throw invalidInput(
                    "lab.delay",
                    "delayMillis must be the only integer field in 1..30000"
            );
        }
        activeDelayCount.incrementAndGet();
        try {
            Thread.sleep((Long) rawDelayMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorkerException(
                    WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "lab.delay",
                    "Android Worker Lab delay was interrupted",
                    error
            );
        } finally {
            activeDelayCount.decrementAndGet();
        }
        return "null";
    }

    private static String fail(Map<String, Object> payload) {
        if (!payload.isEmpty()) {
            throw invalidInput(
                    "lab.fail",
                    "fail payload must be an empty object"
            );
        }
        throw new WorkerException(
                WorkerErrorCode.EVENT_EXECUTION_FAILED,
                "lab.fail",
                "Android Worker Lab requested Handler failure",
                null
        );
    }

    private static WorkerException invalidInput(
            String operation,
            String message
    ) {
        return new WorkerException(
                WorkerErrorCode.EVENT_INPUT_INVALID,
                operation,
                message,
                null
        );
    }
}
