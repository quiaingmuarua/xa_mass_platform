package com.xa.mass.workerdelivery.adapter.message;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AdapterMessageDefinitionManager<R> {

    private final Map<
            String,
            AdapterMessageDefinition<?, ? extends R>
            > definitions;

    public AdapterMessageDefinitionManager(
            Map<
                    String,
                    ? extends AdapterMessageDefinition<?, ? extends R>
                    > definitions
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, AdapterMessageDefinition<?, ? extends R>> copy =
                new LinkedHashMap<>();
        for (Map.Entry<
                String,
                ? extends AdapterMessageDefinition<?, ? extends R>
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

    public R dispatch(
            String workerId,
            WorkerConnectionMessage message
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        Objects.requireNonNull(message, "message");
        String messageType = message.messageType();
        AdapterMessageDefinition<?, ? extends R> definition =
                definitions.get(messageType);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "Unsupported Worker connection message: "
                            + messageType
            );
        }
        return definition.invoke(workerId, message.payload());
    }
}
