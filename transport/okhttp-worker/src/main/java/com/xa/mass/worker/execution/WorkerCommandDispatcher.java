package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class WorkerCommandDispatcher
        implements WorkerCommandExecutor {

    private final WorkerDeliveryCodec codec;
    private final WorkerEventDefinitionManager eventDefinitionManager;
    private final LongSupplier nowMillis;

    public WorkerCommandDispatcher(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        this(
                definitions,
                new WorkerDeliveryCodec(),
                System::currentTimeMillis
        );
    }

    WorkerCommandDispatcher(
            Collection<? extends WorkerEventDefinition<?>> definitions,
            WorkerDeliveryCodec codec,
            LongSupplier nowMillis
    ) {
        this.eventDefinitionManager =
                new WorkerEventDefinitionManager(definitions);
        this.codec = requirePresent(codec, "codec");
        this.nowMillis = requirePresent(nowMillis, "nowMillis");
    }

    @Override
    public Optional<WorkerResult> execute(String encodedCommand) {
        WorkerCommand command = codec.decodeWorkerCommand(encodedCommand);
        if (command == null) {
            throw new WorkerException(
                    WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                    "command.decode",
                    null,
                    null
            );
        }
        if (nowMillis.getAsLong() >= command.executeBeforeMillis()) {
            return Optional.empty();
        }

        return Optional.of(executeEvent(command));
    }

    private WorkerResult executeEvent(WorkerCommand command) {
        Map<String, Object> parameters;
        try {
            parameters = Jsons.parseObject(command.payload());
        } catch (IllegalArgumentException error) {
            return result(command, "1400", "null");
        }
        try {
            String payload = eventDefinitionManager.dispatch(
                    command.src().wireValue(),
                    command.messageType(),
                    parameters
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
            if (error.errorCode() == WorkerErrorCode.EVENT_NOT_FOUND) {
                return result(command, "1404", "null");
            }
            return result(command, "1500", "null");
        } catch (Exception error) {
            return result(command, "1500", "null");
        }
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

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " must be present"
            );
        }
        return value;
    }
}
