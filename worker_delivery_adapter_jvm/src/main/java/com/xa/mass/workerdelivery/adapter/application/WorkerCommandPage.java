package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkerCommandPage(
        Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId,
        String nextCursor
) {
    public WorkerCommandPage {
        if (workerCommandsByWorkerId == null) {
            throw new IllegalArgumentException(
                    "workerCommandsByWorkerId must be present"
            );
        }
        workerCommandsByWorkerId = Collections.unmodifiableMap(
                new LinkedHashMap<>(workerCommandsByWorkerId)
        );
        if (nextCursor != null && !isDecimal(nextCursor)) {
            throw new IllegalArgumentException(
                    "nextCursor must be a non-negative decimal cursor"
            );
        }
    }

    private static boolean isDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
