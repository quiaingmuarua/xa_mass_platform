package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.REPLACED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.TRANSPORT_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryWorkerSessionDirectoryTest {

    @Test
    void replacementAndStaleUnbindPreserveNewGeneration() {
        InMemoryWorkerSessionDirectory directory =
                new InMemoryWorkerSessionDirectory();
        FakeConnection first = new FakeConnection(DELIVERED);
        FakeConnection second = new FakeConnection(DELIVERED);

        WorkerSessionToken firstToken =
                directory.bind("worker-1", first);
        WorkerSessionToken secondToken =
                directory.bind("worker-1", second);
        directory.unbind(firstToken);

        assertThat(first.closedReasons).containsExactly(REPLACED);
        assertThat(directory.isCurrent(firstToken)).isFalse();
        assertThat(directory.isCurrent(secondToken)).isTrue();
        assertThat(secondToken.generation())
                .isGreaterThan(firstToken.generation());
        assertThat(directory.activeSessionCount()).isEqualTo(1);
    }

    @Test
    void missingSessionRejectsBeforeSend() {
        InMemoryWorkerSessionDirectory directory =
                new InMemoryWorkerSessionDirectory();

        assertThat(directory.deliver("worker-1", command()))
                .isEqualTo(REJECTED_BEFORE_SEND);
    }

    @Test
    void unknownSendRemovesAndClosesCurrentConnection() {
        InMemoryWorkerSessionDirectory directory =
                new InMemoryWorkerSessionDirectory();
        FakeConnection connection = new FakeConnection(UNKNOWN);
        WorkerSessionToken token =
                directory.bind("worker-1", connection);

        CommandDeliveryAttempt result =
                directory.deliver("worker-1", command());

        assertThat(result).isEqualTo(UNKNOWN);
        assertThat(directory.isCurrent(token)).isFalse();
        assertThat(connection.closedReasons)
                .containsExactly(TRANSPORT_ERROR);
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

        private FakeConnection(CommandDeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                WorkerCommandEnvelope command
        ) {
            return attempt;
        }

        @Override
        public void close(WorkerConnectionCloseReason reason) {
            closedReasons.add(reason);
        }
    }
}
