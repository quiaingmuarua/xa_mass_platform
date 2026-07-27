package com.xa.mass.server.workerdelivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface WorkerDeliveryRuntime {

    WorkerCommandEnvelope consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    );

    WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    );

    int appendSeedResults(List<SeedResult> results);

    record WorkerCommandPage(
            Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId,
            String nextCursor
    ) {
        public WorkerCommandPage {
            if (workerCommandsByWorkerId == null) {
                throw new IllegalArgumentException(
                        "workerCommandsByWorkerId must be present"
                );
            }
            var commands = new LinkedHashMap<>(workerCommandsByWorkerId);
            commands.forEach((workerId, command) -> {
                if (workerId == null || workerId.isBlank()) {
                    throw new IllegalArgumentException(
                            "workerId must be non-blank"
                    );
                }
                if (command == null) {
                    throw new IllegalArgumentException(
                            "Worker command must be present"
                    );
                }
            });
            workerCommandsByWorkerId = Collections.unmodifiableMap(commands);
            if (nextCursor != null && !isDecimal(nextCursor)) {
                throw new IllegalArgumentException(
                        "nextCursor must be a Redis cursor or null"
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
}
