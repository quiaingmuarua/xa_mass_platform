package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class WorkerCommandDispatcher
        implements WorkerCommandExecutor {

    private final Map<String, WorkerEventDefinition<?>> definitions;

    private WorkerCommandDispatcher(
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions
    ) {
        definitions = effectiveDefinitions(definitionExtensions);
    }

    public static WorkerCommandDispatcher forWorker() {
        return forWorker(Collections.emptyList());
    }

    public static WorkerCommandDispatcher forWorker(
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions
    ) {
        return new WorkerCommandDispatcher(definitionExtensions);
    }

    @Override
    public Optional<WorkerResult> execute(WorkerCommand command) {
        if (command == null) {
            throw new WorkerException(
                    WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                    "command.execute",
                    null,
                    null
            );
        }
        if (System.currentTimeMillis()
                >= command.executeBeforeMillis()) {
            return Optional.empty();
        }

        return Optional.of(executeEvent(command));
    }

    private WorkerResult executeEvent(WorkerCommand command) {
        try {
            WorkerEventDefinition<?> definition =
                    definitions.get(definitionKey(
                            command.src().wireValue(),
                            command.messageType()
                    ));
            if (definition == null) {
                return result(command, "1404", "null");
            }
            String payload = invokeDefinition(
                    definition,
                    command.payload()
            );
            if (payload == null || payload.isEmpty()) {
                return result(command, "1500", "null");
            }
            return result(command, "200", payload);
        } catch (WorkerException error) {
            if (error.errorCode()
                    == WorkerErrorCode.EVENT_INPUT_INVALID) {
                return result(command, "1400", "null");
            }
            return result(command, "1500", "null");
        } catch (Exception error) {
            return result(command, "1500", "null");
        }
    }

    private static <P> String invokeDefinition(
            WorkerEventDefinition<P> definition,
            String payload
    ) throws Exception {
        P parameters;
        try {
            parameters = definition
                    .parameterResolver()
                    .resolve(payload);
        } catch (WorkerException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new WorkerException(
                    WorkerErrorCode.EVENT_INPUT_INVALID,
                    "event.resolve",
                    null,
                    error
            );
        }
        return definition.handler().execute(parameters);
    }

    private static WorkerResult result(
            WorkerCommand command,
            String outcomeCode,
            String payload
    ) {
        return new WorkerResult(
                command.messageId(),
                command.src(),
                command.messageType(),
                outcomeCode,
                payload,
                command.forward()
        );
    }

    private static Map<String, WorkerEventDefinition<?>>
    effectiveDefinitions(
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions
    ) {
        Objects.requireNonNull(
                definitionExtensions,
                "definitionExtensions"
        );
        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        addDefinitions(definitions, builtInDefinitions());
        addDefinitions(definitions, definitionExtensions);
        return Collections.unmodifiableMap(definitions);
    }

    private static void addDefinitions(
            Map<String, WorkerEventDefinition<?>> target,
            Collection<? extends WorkerEventDefinition<?>> additions
    ) {
        for (WorkerEventDefinition<?> definition : additions) {
            WorkerEventDefinition<?> present = Objects.requireNonNull(
                    definition,
                    "definition"
            );
            String key = definitionKey(
                    present.src(),
                    present.eventCode()
            );
            if (target.putIfAbsent(key, present) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Worker event: "
                                + present.src()
                                + "/"
                                + present.eventCode()
                );
            }
        }
    }

    private static String definitionKey(String src, String eventCode) {
        if (src == null || src.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "src must be non-blank"
            );
        }
        WorkerMessageEndpoint endpoint =
                WorkerMessageEndpoint.fromWire(src);
        if (endpoint == WorkerMessageEndpoint.WORKER) {
            throw new IllegalArgumentException(
                    "Worker event src cannot be WORKER"
            );
        }
        if (eventCode == null || eventCode.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "eventCode must be non-blank"
            );
        }
        return endpoint.wireValue() + ":" + eventCode;
    }

    private static List<WorkerEventDefinition<?>> builtInDefinitions() {
        return Collections.emptyList();
    }
}
