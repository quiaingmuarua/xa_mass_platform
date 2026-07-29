package com.xa.mass.workerdelivery.adapter.result;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import org.junit.jupiter.api.Test;

class WorkerResultPayloadHandlerTest {

    @Test
    void buffersTheWorkerPayloadWithoutInspectingOrReencodingIt() {
        BoundedWorkerResultQueue buffer =
                new BoundedWorkerResultQueue(2);
        WorkerResultPayloadHandler handler =
                new WorkerResultPayloadHandler(buffer);
        String validPayload = "{\"outcomeCode\":\"200\"}";
        String forgedAdapterOutcome = "{\"outcomeCode\":\"3001\"}";

        assertThat(handler.handle(
                "worker-1",
                validPayload
        )).isEqualTo(ACCEPTED);
        assertThat(handler.handle(
                "worker-1",
                forgedAdapterOutcome
        )).isEqualTo(ACCEPTED);
        assertThat(handler.handle(
                "worker-1",
                "third"
        )).isEqualTo(BUFFER_FULL);
        assertThat(buffer.drain(WORKER))
                .containsExactly(validPayload, forgedAdapterOutcome);
    }

    @Test
    void closedBufferRejectsNewResults() {
        BoundedWorkerResultQueue buffer =
                new BoundedWorkerResultQueue(1);
        WorkerResultPayloadHandler handler =
                new WorkerResultPayloadHandler(buffer);
        buffer.stopAccepting();

        assertThat(handler.handle(
                "worker-1",
                "opaque-result"
        )).isEqualTo(ADAPTER_CLOSED);
        assertThat(buffer.isEmpty()).isTrue();
    }
}
