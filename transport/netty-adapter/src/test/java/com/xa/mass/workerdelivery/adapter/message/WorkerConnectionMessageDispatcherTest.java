package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.WorkerMessageHandlingResult.UNSUPPORTED_MESSAGE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_COMMAND;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_RESULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerConnectionMessageDispatcherTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    @Test
    void dispatchesOnlyToTheStaticallyInstalledHandler() {
        WorkerConnectionMessageHandler<TaskItemResultMessage> handler =
                new StubHandler();
        WorkerConnectionMessageDispatcher dispatcher =
                new WorkerConnectionMessageDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(
                "worker-1",
                resultMessage()
        )).isEqualTo(ACCEPTED);
        assertThat(dispatcher.dispatch(
                "worker-1",
                commandMessage()
        )).isEqualTo(UNSUPPORTED_MESSAGE);
    }

    @Test
    void rejectsDuplicateAndMismatchedHandlers() {
        StubHandler handler = new StubHandler();

        assertThatThrownBy(() ->
                new WorkerConnectionMessageDispatcher(
                        List.of(handler, handler)
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        assertThatThrownBy(() ->
                new WorkerConnectionMessageDispatcher(
                        List.of(new MismatchedHandler())
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    private static TaskItemResultMessage resultMessage() {
        return new TaskItemResultMessage(
                TestMessages.successResult(COMMAND_ID)
        );
    }

    private static TaskItemCommandMessage commandMessage() {
        return new TaskItemCommandMessage(new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                100,
                "item"
        ));
    }

    private static final class StubHandler
            implements WorkerConnectionMessageHandler<
            TaskItemResultMessage> {

        @Override
        public WorkerConnectionMessageType messageType() {
            return TASK_ITEM_RESULT;
        }

        @Override
        public Class<TaskItemResultMessage> messageClass() {
            return TaskItemResultMessage.class;
        }

        @Override
        public WorkerMessageHandlingResult handle(
                String workerId,
                TaskItemResultMessage message
        ) {
            return ACCEPTED;
        }
    }

    private static final class MismatchedHandler
            implements WorkerConnectionMessageHandler<
            TaskItemResultMessage> {

        @Override
        public WorkerConnectionMessageType messageType() {
            return TASK_ITEM_COMMAND;
        }

        @Override
        public Class<TaskItemResultMessage> messageClass() {
            return TaskItemResultMessage.class;
        }

        @Override
        public WorkerMessageHandlingResult handle(
                String workerId,
                TaskItemResultMessage message
        ) {
            return ACCEPTED;
        }
    }
}
