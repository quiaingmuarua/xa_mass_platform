package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.REPLACED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.TRANSPORT_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryWorkerConnectionRegistryTest {

    @Test
    void replacementAndOldUnbindPreserveCurrentConnection() {
        InMemoryWorkerConnectionRegistry registry =
                new InMemoryWorkerConnectionRegistry();
        FakeConnection first = new FakeConnection(DELIVERED);
        FakeConnection second = new FakeConnection(DELIVERED);

        registry.bind("worker-1", first);
        registry.bind("worker-1", second);
        registry.unbind("worker-1", first);

        assertThat(first.closedReasons).containsExactly(REPLACED);
        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(DELIVERED);
        assertThat(first.deliverCount).isZero();
        assertThat(second.deliverCount).isEqualTo(1);
        assertThat(registry.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void missingConnectionRejectsBeforeSend() {
        InMemoryWorkerConnectionRegistry registry =
                new InMemoryWorkerConnectionRegistry();

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(REJECTED_BEFORE_SEND);
    }

    @Test
    void unknownSendRemovesAndClosesCurrentConnection() {
        InMemoryWorkerConnectionRegistry registry =
                new InMemoryWorkerConnectionRegistry();
        FakeConnection connection = new FakeConnection(UNKNOWN);
        registry.bind("worker-1", connection);

        CommandDeliveryAttempt result =
                registry.deliver("worker-1", command());

        assertThat(result).isEqualTo(UNKNOWN);
        assertThat(registry.activeConnectionCount()).isZero();
        assertThat(connection.closedReasons)
                .containsExactly(TRANSPORT_ERROR);
    }

    @Test
    void failedOldSendCannotRemoveConnectionBoundDuringSend() {
        InMemoryWorkerConnectionRegistry registry =
                new InMemoryWorkerConnectionRegistry();
        FakeConnection replacement = new FakeConnection(DELIVERED);
        WorkerConnection old = new WorkerConnection() {
            @Override
            public CommandDeliveryAttempt deliver(
                    WorkerCommandEnvelope command
            ) {
                registry.bind("worker-1", replacement);
                return UNKNOWN;
            }

            @Override
            public void close(WorkerConnectionCloseReason reason) {
            }
        };
        registry.bind("worker-1", old);

        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(UNKNOWN);
        assertThat(registry.deliver("worker-1", command()))
                .isEqualTo(DELIVERED);
        assertThat(replacement.deliverCount).isEqualTo(1);
        assertThat(registry.activeConnectionCount()).isEqualTo(1);
    }

    private static WorkerCommandEnvelope command() {
        return new WorkerCommandEnvelope(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                WorkerMessageType.TASK_ITEM,
                2_000,
                "{}"
        );
    }

    private static final class FakeConnection
            implements WorkerConnection {
        private final CommandDeliveryAttempt attempt;
        private final List<WorkerConnectionCloseReason> closedReasons =
                new ArrayList<>();
        private int deliverCount;

        private FakeConnection(CommandDeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                WorkerCommandEnvelope command
        ) {
            deliverCount++;
            return attempt;
        }

        @Override
        public void close(WorkerConnectionCloseReason reason) {
            closedReasons.add(reason);
        }
    }
}
