package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Fixed Adapter-local WorkerResult event registry used by channel handlers. */
public final class AdapterWorkerEventDispatcher {

    private static final System.Logger LOGGER = System.getLogger(
            AdapterWorkerEventDispatcher.class.getName()
    );

    private final Duration sendTimeLimit;
    private final Function<String, CompletionStage<Void>> identifyWorker;
    private final Map<String, AdapterWorkerEventDefinition> definitions;

    public AdapterWorkerEventDispatcher(
            Duration sendTimeLimit,
            Function<String, CompletionStage<Void>> identifyWorker
    ) {
        if (sendTimeLimit == null || sendTimeLimit.isZero()
                || sendTimeLimit.isNegative()
                || sendTimeLimit.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "sendTimeLimit must be positive"
            );
        }
        this.sendTimeLimit = sendTimeLimit;
        this.identifyWorker = Objects.requireNonNull(
                identifyWorker,
                "identifyWorker"
        );
        definitions = Map.of(
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                this::identify
        );
    }

    public CompletionStage<Optional<WorkerCommand>> dispatch(
            WorkerResult result
    ) {
        Objects.requireNonNull(result, "result");
        AdapterWorkerEventDefinition definition = definitions.get(
                result.messageType()
        );
        if (definition != null) {
            return definition.handle(result);
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} messageType={2}",
                WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID.code(),
                "adapter.dropUnknownWorkerEvent",
                result.messageType()
        );
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private CompletionStage<Optional<WorkerCommand>> identify(
            WorkerResult result
    ) {
        if (result.dst() != ADAPTER
                || !"200".equals(result.outcomeCode())
                || !result.forward().isEmpty()
                || result.payload().isBlank()) {
            return failed(new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID,
                    "adapter.identifyWorker",
                    "Worker identity result is invalid",
                    null
            ));
        }

        CompletionStage<Void> verification;
        try {
            verification = identifyWorker.apply(result.payload());
        } catch (RuntimeException error) {
            return failed(error);
        }
        if (verification == null) {
            return failed(new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.WORKER_MESSAGE_INVALID,
                    "adapter.identifyWorker",
                    "Worker identity verification returned null",
                    null
            ));
        }
        return verification.handle((ignored, failure) -> {
            if (failure == null) {
                return Optional.empty();
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof WorkerDeliveryAdapterException classified
                    && classified.errorCode()
                    == WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED) {
                return Optional.of(closeCommand());
            }
            throw new CompletionException(cause);
        });
    }

    private WorkerCommand closeCommand() {
        return new WorkerCommand(
                UUID.randomUUID().toString(),
                ADAPTER,
                WORKER,
                WORKER_CONNECTION_CLOSE_EVENT_CODE,
                Math.addExact(
                        System.currentTimeMillis(),
                        sendTimeLimit.toMillis()
                ),
                "null",
                ""
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    @FunctionalInterface
    private interface AdapterWorkerEventDefinition {
        CompletionStage<Optional<WorkerCommand>> handle(WorkerResult result);
    }
}
