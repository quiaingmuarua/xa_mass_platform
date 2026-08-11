package com.xa.mass.workerdelivery.adapter.result;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.INVALID_RESULT;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.message.WorkerResultPayloadHandler;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import org.junit.jupiter.api.Test;

class WorkerResultPayloadHandlerTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void validatesWorkerOutcomeAndQueuesOriginalEncoding() {
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(1);
        WorkerResultPayloadHandler handler =
                new WorkerResultPayloadHandler(queue);
        String valid = result("200");

        assertThat(handler.handle("worker-1", valid)).isEqualTo(ACCEPTED);
        assertThat(handler.handle("worker-1", result("3500")))
                .isEqualTo(BUFFER_FULL);
        assertThat(queue.drain()).containsExactly(valid);
    }

    @Test
    void rejectsMalformedAndAdapterOwnedOutcomes() {
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(2);
        WorkerResultPayloadHandler handler =
                new WorkerResultPayloadHandler(queue);

        assertThat(handler.handle("worker-1", "{bad-json"))
                .isEqualTo(INVALID_RESULT);
        assertThat(handler.handle(
                "worker-1",
                result(Integer.toString(
                        WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED.code()
                ))
        ))
                .isEqualTo(INVALID_RESULT);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void closedQueueRejectsNewResults() {
        BoundedWorkerResultQueue queue = new BoundedWorkerResultQueue(1);
        WorkerResultPayloadHandler handler =
                new WorkerResultPayloadHandler(queue);
        queue.stopAccepting();

        assertThat(handler.handle("worker-1", result("200")))
                .isEqualTo(ADAPTER_CLOSED);
    }

    private String result(String outcomeCode) {
        return codec.encodeWorkerResult(new WorkerResult(
                "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
                TASK,
                "test.observe",
                outcomeCode,
                "null",
                "context"
        ));
    }
}
