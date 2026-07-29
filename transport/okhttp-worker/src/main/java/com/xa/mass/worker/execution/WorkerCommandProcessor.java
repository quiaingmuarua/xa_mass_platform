package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class WorkerCommandProcessor {

    private final WorkerEventDefinitionManager eventDefinitionManager;
    private final LongSupplier nowMillis;

    public WorkerCommandProcessor(
            Map<String, ? extends WorkerEventDefinition<?>> eventDefinitions
    ) {
        this(
                eventDefinitions,
                System::currentTimeMillis
        );
    }

    WorkerCommandProcessor(
            Map<String, ? extends WorkerEventDefinition<?>> eventDefinitions,
            LongSupplier nowMillis
    ) {
        this.eventDefinitionManager =
                new WorkerEventDefinitionManager(eventDefinitions);
        this.nowMillis = nowMillis;
    }

    public Optional<WorkerResult> process(WorkerCommand command) {
        if (nowMillis.getAsLong() >= command.executeBeforeMillis()) {
            return Optional.empty();
        }
        ExecutionResult execution = execute(
                command.messageType(),
                command.payload()
        );
        return Optional.of(new WorkerResult(
                command.messageId(),
                command.src(),
                command.messageType(),
                execution.outcomeCode(),
                execution.payload(),
                command.forward()
        ));
    }

    private ExecutionResult execute(
            String eventCode,
            String encodedParameters
    ) {
        Map<String, Object> parameters;
        try {
            parameters = Jsons.parseObject(encodedParameters);
        } catch (IllegalArgumentException error) {
            return ExecutionResult.failure("1400");
        }
        try {
            String result = eventDefinitionManager.dispatch(
                    eventCode,
                    parameters
            );
            if (result == null || result.isEmpty()) {
                return ExecutionResult.failure("1500");
            }
            return new ExecutionResult(
                    "200",
                    result
            );
        } catch (WorkerException error) {
            if (error.errorCode()
                    == WorkerErrorCode.EVENT_INPUT_INVALID) {
                return ExecutionResult.failure("1400");
            }
            if (error.errorCode() == WorkerErrorCode.EVENT_NOT_FOUND) {
                return ExecutionResult.failure("1404");
            }
            return ExecutionResult.failure("1500");
        } catch (Exception error) {
            return ExecutionResult.failure("1500");
        }
    }

    private static final class ExecutionResult {

        private final String outcomeCode;
        private final String payload;

        private ExecutionResult(
                String outcomeCode,
                String payload
        ) {
            this.outcomeCode = outcomeCode;
            this.payload = payload;
        }

        private String outcomeCode() {
            return outcomeCode;
        }

        private String payload() {
            return payload;
        }

        private static ExecutionResult failure(String code) {
            return new ExecutionResult(code, "null");
        }
    }
}
