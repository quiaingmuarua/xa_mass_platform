package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import java.util.List;
import java.util.Objects;

/** Lightweight outcome for one batch attempt; it never owns batch items. */
public record BatchProcessResult(
        WorkerDeliveryAdapterErrorCode errorCode,
        List<Integer> requeueIndexes
) {

    private static final BatchProcessResult COMPLETED =
            new BatchProcessResult(null, List.of());

    public BatchProcessResult {
        requeueIndexes = List.copyOf(
                Objects.requireNonNull(requeueIndexes, "requeueIndexes")
        );
        if (errorCode == null) {
            if (!requeueIndexes.isEmpty()) {
                throw new IllegalArgumentException(
                        "completed result must not contain requeue indexes"
                );
            }
        } else if (requeueIndexes.isEmpty()) {
            throw new IllegalArgumentException(
                    "requeue result must contain at least one index"
            );
        }
        int previous = -1;
        for (int index : requeueIndexes) {
            if (index < 0 || index <= previous) {
                throw new IllegalArgumentException(
                        "requeue indexes must be non-negative, unique, "
                                + "and ordered"
                );
            }
            previous = index;
        }
    }

    public static BatchProcessResult completed() {
        return COMPLETED;
    }

    public static BatchProcessResult requeue(
            WorkerDeliveryAdapterErrorCode errorCode,
            List<Integer> itemIndexes
    ) {
        return new BatchProcessResult(
                Objects.requireNonNull(errorCode, "errorCode"),
                itemIndexes
        );
    }

    public boolean isCompleted() {
        return errorCode == null;
    }
}
