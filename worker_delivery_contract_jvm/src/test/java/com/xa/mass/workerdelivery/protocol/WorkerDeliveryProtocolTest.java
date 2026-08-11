package com.xa.mass.workerdelivery.protocol;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass;
import org.junit.jupiter.api.Test;

final class WorkerDeliveryProtocolTest {

    private static final String MESSAGE_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void commandHasStableWireAndRoundTrips() {
        WorkerCommand command = command();
        String encoded = codec.encodeWorkerCommand(command);

        assertEquals(
                "{\"dst\":\"WORKER\",\"executeBeforeMillis\":1234567890,"
                        + "\"forward\":\"context\",\"messageId\":\""
                        + MESSAGE_ID
                        + "\",\"messageType\":\"telecom.phone.inspect\","
                        + "\"payload\":\"{\\\"phoneNumber\\\":"
                        + "\\\"+14155552671\\\"}\",\"src\":\"TASK\"}",
                encoded
        );
        assertEquals(command, codec.decodeWorkerCommand(encoded));
    }

    @Test
    void resultHasStableWireAndRoundTrips() {
        WorkerResult result = result("200");
        String encoded = codec.encodeWorkerResult(result);

        assertEquals(
                "{\"dst\":\"TASK\",\"forward\":\"context\","
                        + "\"messageId\":\"" + MESSAGE_ID
                        + "\",\"messageType\":\"telecom.phone.inspect\","
                        + "\"outcomeCode\":\"200\","
                        + "\"payload\":\"{\\\"isValid\\\":true}\"}",
                encoded
        );
        assertEquals(result, codec.decodeWorkerResult(encoded));
    }

    @Test
    void resultFromCommandOwnsCorrelationMapping() {
        WorkerCommand command = command();

        WorkerResult result = WorkerResult.fromCommand(
                command,
                "3302",
                "not found"
        );

        assertEquals(command.messageId(), result.messageId());
        assertEquals(command.src(), result.dst());
        assertEquals(command.messageType(), result.messageType());
        assertEquals("3302", result.outcomeCode());
        assertEquals("not found", result.payload());
        assertEquals(command.forward(), result.forward());
    }

    @Test
    void bindHasStableWireAndRoundTrips() {
        WorkerConnectionBind bind = new WorkerConnectionBind(MESSAGE_ID);
        String encoded = codec.encodeWorkerConnectionBind(bind);

        assertEquals(
                "{\"workerId\":\"" + MESSAGE_ID + "\"}",
                encoded
        );
        assertEquals(bind, codec.decodeWorkerConnectionBind(encoded));
        assertNull(codec.decodeWorkerConnectionBind(
                "{\"workerId\":\"" + MESSAGE_ID + "\",\"extra\":true}"
        ));
        assertNull(codec.decodeWorkerConnectionBind(
                "{\"workerId\":\"not-a-uuid\"}"
        ));
    }

    @Test
    void endpointWireValuesAreExplicit() {
        assertEquals(TASK, WorkerMessageEndpoint.fromWire("TASK"));
        assertEquals(SYSTEM, WorkerMessageEndpoint.fromWire("SYSTEM"));
        assertEquals(ADAPTER, WorkerMessageEndpoint.fromWire("ADAPTER"));
        assertEquals(WORKER, WorkerMessageEndpoint.fromWire("WORKER"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerMessageEndpoint.fromWire("task")
        );
    }

    @Test
    void codecsRejectUnknownFieldsAndDirections() {
        assertNull(codec.decodeWorkerCommand(
                "{\"dst\":\"WORKER\",\"executeBeforeMillis\":1,"
                        + "\"forward\":\"context\",\"messageId\":\""
                        + MESSAGE_ID
                        + "\",\"messageType\":\"event\",\"payload\":\"{}\","
                        + "\"src\":\"TASK\",\"extra\":true}"
        ));
        assertNull(codec.decodeWorkerCommand(
                "{\"dst\":\"TASK\",\"executeBeforeMillis\":1,"
                        + "\"forward\":\"context\",\"messageId\":\""
                        + MESSAGE_ID
                        + "\",\"messageType\":\"event\",\"payload\":\"{}\","
                        + "\"src\":\"TASK\"}"
        ));
        assertNull(codec.decodeWorkerResult(
                "{\"dst\":\"WORKER\",\"forward\":\"context\","
                        + "\"messageId\":\"" + MESSAGE_ID
                        + "\",\"messageType\":\"event\","
                        + "\"outcomeCode\":\"200\",\"payload\":\"null\"}"
        ));
    }

    @Test
    void outcomeClassificationUsesOwnerPrefixWithoutWidthValidation() {
        assertEquals(
                WorkerResultOutcomeClass.SUCCESS,
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode("200")
        );
        assertEquals(
                WorkerResultOutcomeClass.WORKER_FAILURE,
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode("33001")
        );
        assertEquals(
                WorkerResultOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode("23001")
        );
        assertEquals(
                WorkerResultOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode("1400")
        );
        assertEquals(
                WorkerResultOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode(
                        "adapter-error"
                )
        );
        assertNull(WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode(" "));
    }

    @Test
    void messageIdIsCanonicalAndTaskForwardIsRequired() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerCommand(
                        MESSAGE_ID.toUpperCase(),
                        TASK,
                        WORKER,
                        "event",
                        1,
                        "{}",
                        "context"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerResult(
                        MESSAGE_ID,
                        TASK,
                        "event",
                        "200",
                        "null",
                        ""
                )
        );
    }

    private static WorkerCommand command() {
        return new WorkerCommand(
                MESSAGE_ID,
                TASK,
                WORKER,
                "telecom.phone.inspect",
                1_234_567_890L,
                "{\"phoneNumber\":\"+14155552671\"}",
                "context"
        );
    }

    private static WorkerResult result(String outcomeCode) {
        return new WorkerResult(
                MESSAGE_ID,
                TASK,
                "telecom.phone.inspect",
                outcomeCode,
                "{\"isValid\":true}",
                "context"
        );
    }
}
