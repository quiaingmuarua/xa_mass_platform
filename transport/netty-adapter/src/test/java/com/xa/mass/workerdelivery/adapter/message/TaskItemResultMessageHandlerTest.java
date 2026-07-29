package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.INVALID_OUTCOME;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import org.junit.jupiter.api.Test;

class TaskItemResultMessageHandlerTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    @Test
    void appliesWorkerOutcomePolicyBeforeBuffering() {
        BoundedWorkerResultQueue buffer =
                new BoundedWorkerResultQueue(1);
        TaskItemResultMessageHandler handler =
                new TaskItemResultMessageHandler(buffer);
        SeedResult accepted = TestMessages.successResult(COMMAND_ID);

        assertThat(handler.handle(
                "worker-1",
                new TaskItemResultMessage(accepted)
        )).isEqualTo(ACCEPTED);
        assertThat(buffer.drainAll()).containsExactly(accepted);

        SeedResult workerFailure = new SeedResult(
                "b6e9e10d-f78b-469e-93ab-864b49c189c1",
                "context",
                "1500",
                null
        );
        assertThat(handler.handle(
                "worker-1",
                new TaskItemResultMessage(workerFailure)
        )).isEqualTo(ACCEPTED);
        assertThat(handler.handle(
                "worker-1",
                new TaskItemResultMessage(new SeedResult(
                        "c7e9e10d-f78b-469e-93ab-864b49c189c1",
                        "context",
                        "3001",
                        null
                ))
        )).isEqualTo(INVALID_OUTCOME);
        assertThat(handler.handle(
                "worker-1",
                new TaskItemResultMessage(
                        TestMessages.successResult(COMMAND_ID)
                )
        )).isEqualTo(BUFFER_FULL);
        assertThat(buffer.drainAll()).containsExactly(workerFailure);
    }

    @Test
    void closedBufferRejectsNewResults() {
        BoundedWorkerResultQueue buffer =
                new BoundedWorkerResultQueue(1);
        TaskItemResultMessageHandler handler =
                new TaskItemResultMessageHandler(buffer);
        buffer.stopAccepting();

        assertThat(handler.handle(
                "worker-1",
                new TaskItemResultMessage(
                        TestMessages.successResult(COMMAND_ID)
                )
        )).isEqualTo(ADAPTER_CLOSED);
        assertThat(buffer.isEmpty()).isTrue();
    }
}
