package com.xa.mass.workerdelivery.adapter.message;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerConnectionMessageDispatcher {

    private final Map<
            WorkerConnectionMessageType,
            WorkerConnectionMessageHandler<?>
            > handlersByType;

    public WorkerConnectionMessageDispatcher(
            Collection<WorkerConnectionMessageHandler<?>> handlers
    ) {
        Objects.requireNonNull(handlers, "handlers");
        EnumMap<
                WorkerConnectionMessageType,
                WorkerConnectionMessageHandler<?>
                > indexed = new EnumMap<>(
                        WorkerConnectionMessageType.class
                );
        for (WorkerConnectionMessageHandler<?> handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            WorkerConnectionMessageType type = Objects.requireNonNull(
                    handler.messageType(),
                    "handler.messageType"
            );
            Class<?> messageClass = Objects.requireNonNull(
                    handler.messageClass(),
                    "handler.messageClass"
            );
            if (messageClass != expectedMessageClass(type)) {
                throw new IllegalArgumentException(
                        "Handler message class does not match " + type
                );
            }
            if (indexed.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Worker message handler for " + type
                );
            }
        }
        handlersByType = Map.copyOf(indexed);
    }

    public WorkerMessageHandlingResult dispatch(
            String workerId,
            WorkerConnectionMessage message
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        Objects.requireNonNull(message, "message");
        WorkerConnectionMessageHandler<?> handler =
                handlersByType.get(message.messageType());
        if (handler == null) {
            return WorkerMessageHandlingResult.UNSUPPORTED_MESSAGE;
        }
        return dispatchTyped(handler, workerId, message);
    }

    private static Class<? extends WorkerConnectionMessage>
    expectedMessageClass(WorkerConnectionMessageType type) {
        return switch (type) {
            case TASK_ITEM_COMMAND -> TaskItemCommandMessage.class;
            case TASK_ITEM_RESULT -> TaskItemResultMessage.class;
        };
    }

    private static <M extends WorkerConnectionMessage>
    WorkerMessageHandlingResult dispatchTyped(
            WorkerConnectionMessageHandler<M> handler,
            String workerId,
            WorkerConnectionMessage message
    ) {
        return handler.handle(
                workerId,
                handler.messageClass().cast(message)
        );
    }
}
