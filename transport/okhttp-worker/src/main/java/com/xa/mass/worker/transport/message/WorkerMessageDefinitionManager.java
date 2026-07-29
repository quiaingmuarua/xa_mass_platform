package com.xa.mass.worker.transport.message;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerMessageDefinitionManager<R> {

    private final Map<
            String,
            WorkerMessageDefinition<?, ? extends R>
            > definitions;

    public WorkerMessageDefinitionManager(
            Map<
                    String,
                    ? extends WorkerMessageDefinition<?, ? extends R>
                    > definitions
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, WorkerMessageDefinition<?, ? extends R>> copy =
                new LinkedHashMap<>();
        for (Map.Entry<
                String,
                ? extends WorkerMessageDefinition<?, ? extends R>
                > entry : definitions.entrySet()) {
            String messageType = entry.getKey();
            if (messageType == null || messageType.isBlank()) {
                throw new IllegalArgumentException(
                        "messageType must be non-blank"
                );
            }
            copy.put(
                    messageType,
                    Objects.requireNonNull(
                            entry.getValue(),
                            "definition"
                    )
            );
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    public R dispatch(WorkerConnectionMessage message) {
        try {
            if (message == null) {
                throw new IllegalArgumentException(
                        "message must be present"
                );
            }
            String messageType = message.messageType();
            WorkerMessageDefinition<?, ? extends R> definition =
                    definitions.get(messageType);
            if (definition == null) {
                throw new IllegalArgumentException(
                        "Unsupported Worker connection message: "
                                + messageType
                );
            }
            return definition.invoke(message.payload());
        } catch (WorkerException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new WorkerException(
                    WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                    "connectionMessage.dispatch",
                    null,
                    error
            );
        }
    }
}
