package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    public Optional<WorkerCommandOutcome> execute(DeliveryCommand command) {
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

    private WorkerCommandOutcome executeEvent(DeliveryCommand command) {
        WorkerEventDefinition<?> definition = definitions.get(
                command.messageType()
        );
        if (definition == null) {
            return failure(WorkerErrorCode.EVENT_NOT_FOUND);
        }
        return invokeDefinition(command, definition);
    }

    private static <P> WorkerCommandOutcome invokeDefinition(
            DeliveryCommand command,
            WorkerEventDefinition<P> definition
    ) {
        P parameters;
        try {
            parameters = definition
                    .parameterResolver()
                    .resolve(command.payload());
        } catch (WorkerException error) {
            return failure(error.errorCode());
        } catch (Exception error) {
            return failure(WorkerErrorCode.EVENT_INPUT_INVALID);
        }

        String payload;
        try {
            payload = definition.handler().execute(parameters);
        } catch (WorkerException error) {
            return failure(error.errorCode());
        } catch (Exception error) {
            return failure(WorkerErrorCode.EVENT_EXECUTION_FAILED);
        }
        if (payload == null || payload.isEmpty()) {
            return failure(WorkerErrorCode.EVENT_RESULT_INVALID);
        }
        return WorkerCommandOutcome.of("200", payload);
    }

    private static WorkerCommandOutcome failure(WorkerErrorCode errorCode) {
        return WorkerCommandOutcome.of(
                Integer.toString(errorCode.code()),
                errorCode.defaultMessage()
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
            String key = present.eventName();
            if (target.putIfAbsent(key, present) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Worker event: "
                                + present.eventName()
                );
            }
        }
    }

}
