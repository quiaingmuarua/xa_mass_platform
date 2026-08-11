package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdapterWorkerEventDispatcherTest {

    private static final String OPAQUE_WORKER_ID = "server-issued-worker";

    @Test
    void identifiesOpaqueWorkerIdWithoutAck() {
        AtomicReference<String> identified = new AtomicReference<>();
        AdapterWorkerEventDispatcher dispatcher = dispatcher(workerId -> {
            identified.set(workerId);
            return CompletableFuture.completedFuture(null);
        });

        Optional<DeliveryCommand> response = dispatcher.dispatch(identity())
                .toCompletableFuture()
                .join();

        assertThat(identified).hasValue(OPAQUE_WORKER_ID);
        assertThat(response).isEmpty();
    }

    @Test
    void hardRouteRejectionReturnsCloseCommand() {
        AdapterWorkerEventDispatcher dispatcher = dispatcher(ignored ->
                failed(WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED)
        );

        DeliveryCommand command = dispatcher.dispatch(identity())
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertThat(command.src()).isEqualTo(ADAPTER);
        assertThat(command.dst()).isEqualTo(WORKER);
        assertThat(command.messageType())
                .isEqualTo(WORKER_CONNECTION_CLOSE_EVENT_CODE);
        assertThat(command.payload()).isEqualTo("null");
        assertThat(command.forward()).isEmpty();
        assertThat(command.executeBeforeMillis())
                .isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void unavailableAndProtocolFailuresDoNotReturnCommands() {
        assertVerificationFailure(
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE
        );
        assertVerificationFailure(
                WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR
        );
    }

    @Test
    void invalidIdentityDoesNotCallGateway() {
        AtomicInteger calls = new AtomicInteger();
        AdapterWorkerEventDispatcher dispatcher = dispatcher(workerId -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        DeliveryReport invalid = DeliveryReport.create(
                ADAPTER,
                OPAQUE_WORKER_ID,
                ADAPTER,
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                "null",
                ""
        );

        assertThatThrownBy(() -> dispatcher.dispatch(invalid)
                .toCompletableFuture()
                .join())
                .hasRootCauseInstanceOf(WorkerDeliveryAdapterException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unknownEventIsDroppedWithoutCallingGateway() {
        AtomicInteger calls = new AtomicInteger();
        AdapterWorkerEventDispatcher dispatcher = dispatcher(workerId -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        DeliveryReport unknown = DeliveryReport.create(
                WORKER,
                OPAQUE_WORKER_ID,
                ADAPTER,
                "adapter.unknown",
                "200",
                "null",
                ""
        );

        assertThat(dispatcher.dispatch(unknown).toCompletableFuture().join())
                .isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void builtInRegistryIsImmutable() throws ReflectiveOperationException {
        AdapterWorkerEventDispatcher dispatcher = dispatcher(ignored ->
                CompletableFuture.completedFuture(null)
        );
        Field field = AdapterWorkerEventDispatcher.class
                .getDeclaredField("definitions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> definitions = (Map<String, Object>) field.get(
                dispatcher
        );

        assertThatThrownBy(() -> definitions.put("another", new Object()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dispatchAcceptsOnlyDeliveryReport() throws ReflectiveOperationException {
        assertThat(AdapterWorkerEventDispatcher.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("dispatch"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .containsExactly(DeliveryReport.class));
    }

    private AdapterWorkerEventDispatcher dispatcher(
            java.util.function.Function<
                    String,
                    CompletionStage<Void>
                    > identify
    ) {
        return new AdapterWorkerEventDispatcher(
                Duration.ofSeconds(1),
                identify
        );
    }

    private DeliveryReport identity() {
        return DeliveryReport.create(
                WORKER,
                OPAQUE_WORKER_ID,
                ADAPTER,
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                "null",
                ""
        );
    }

    private void assertVerificationFailure(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        AdapterWorkerEventDispatcher dispatcher = dispatcher(ignored ->
                failed(errorCode)
        );
        assertThatThrownBy(() -> dispatcher.dispatch(identity())
                .toCompletableFuture()
                .join())
                .hasRootCauseInstanceOf(WorkerDeliveryAdapterException.class)
                .rootCause()
                .satisfies(error -> assertThat(
                        ((WorkerDeliveryAdapterException) error).errorCode()
                ).isEqualTo(errorCode));
    }

    private static CompletionStage<Void> failed(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        CompletableFuture<Void> failure = new CompletableFuture<>();
        failure.completeExceptionally(new WorkerDeliveryAdapterException(
                errorCode,
                "gateway.verifyWorkerRoute",
                "Route verification failed",
                null
        ));
        return failure;
    }
}
