package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import org.junit.jupiter.api.Test;

class WorkerDeliveryProtocolTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void workerConnectionBindUsesItsOwnStrictWireContract() {
        WorkerConnectionBind bind = new WorkerConnectionBind("worker-1");
        String encoded = "{\"workerId\":\"worker-1\"}";

        assertEquals(encoded, codec.encodeWorkerConnectionBind(bind));
        assertEquals(bind, codec.decodeWorkerConnectionBind(encoded));
        assertNull(codec.decodeWorkerConnectionBind(
                "{\"workerId\":\"\"}"
        ));
        assertNull(codec.decodeWorkerConnectionBind(
                "{\"workerId\":\"worker-1\",\"extra\":true}"
        ));
    }

    @Test
    void workerCommandMatchesThePythonGoldenJson() {
        String encoded = """
                {"commandId":"a5e9e10d-f78b-469e-93ab-864b49c189c1",\
                "executeBeforeMillis":123456,"messageType":"TASK_ITEM",\
                "opaqueItem":"opaque-command-item"}\
                """;
        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                123_456,
                "opaque-command-item"
        );

        assertEquals(command, codec.decodeWorkerCommand(encoded));
        assertEquals(encoded, codec.encodeWorkerCommand(command));
        assertNull(codec.decodeWorkerCommand(
                encoded.replace("\"opaqueItem\"", "\"unknown\"")
        ));
    }

    @Test
    void deliverSeedMatchesThePythonGoldenJson() {
        String encoded = """
                {"opaqueDeliveryItem":"item","opaqueResultContext":"context",\
                "workerId":"worker-1"}\
                """;
        DeliverSeed seed = new DeliverSeed(
                "worker-1",
                "item",
                "context"
        );

        assertEquals(seed, codec.decodeDeliverSeed(encoded));
        assertEquals(encoded, codec.encodeDeliverSeed(seed));
        assertNull(codec.decodeDeliverSeed(
                encoded.replace("\"workerId\"", "\"unknown\"")
        ));
    }

    @Test
    void seedResultMatchesThePythonGoldenJson() {
        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        String encoded = "{\"commandId\":\"" + COMMAND_ID + "\","
                + "\"opaqueResultContext\":\"context\","
                + "\"opaqueResultPayload\":\"null\","
                + "\"outcomeCode\":\"200\"}";

        assertEquals(encoded, codec.encodeSeedResult(result));
        assertEquals(result, codec.decodeSeedResult(encoded));
        assertNull(codec.decodeSeedResult(
                encoded.replace("\"outcomeCode\"", "\"unknown\"")
        ));
    }

    @Test
    void workerConnectionMessagesCarryEncodedRealDtos() {
        WorkerConnectionMessage bindMessage =
                new WorkerConnectionMessage(
                        WorkerConnectionMessageType.WORKER_BIND.name(),
                        codec.encodeWorkerConnectionBind(
                                new WorkerConnectionBind("worker-1")
                        )
                );
        String bindJson = "{\"messageType\":\"WORKER_BIND\","
                + "\"payload\":\"{\\\"workerId\\\":\\\"worker-1\\\"}\"}";
        assertEquals(
                bindJson,
                codec.encodeWorkerConnectionMessage(bindMessage)
        );
        assertEquals(
                bindMessage,
                codec.decodeWorkerConnectionMessage(bindJson)
        );

        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                123_456,
                "opaque-command-item"
        );
        String encodedCommand = codec.encodeWorkerCommand(command);
        WorkerConnectionMessage commandMessage =
                new WorkerConnectionMessage(
                        WorkerConnectionMessageType
                                .TASK_ITEM_COMMAND.name(),
                        encodedCommand
                );
        String commandJson = "{\"messageType\":\"TASK_ITEM_COMMAND\","
                + "\"payload\":\"{\\\"commandId\\\":\\\"" + COMMAND_ID
                + "\\\",\\\"executeBeforeMillis\\\":123456,"
                + "\\\"messageType\\\":\\\"TASK_ITEM\\\","
                + "\\\"opaqueItem\\\":\\\"opaque-command-item\\\"}\"}";
        assertEquals(
                commandJson,
                codec.encodeWorkerConnectionMessage(commandMessage)
        );
        assertEquals(
                commandMessage,
                codec.decodeWorkerConnectionMessage(commandJson)
        );
        assertEquals(command, codec.decodeWorkerCommand(
                commandMessage.payload()
        ));

        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        WorkerConnectionMessage resultMessage =
                new WorkerConnectionMessage(
                        WorkerConnectionMessageType
                                .TASK_ITEM_RESULT.name(),
                        codec.encodeSeedResult(result)
                );
        String resultJson = "{\"messageType\":\"TASK_ITEM_RESULT\","
                + "\"payload\":\"{\\\"commandId\\\":\\\"" + COMMAND_ID
                + "\\\",\\\"opaqueResultContext\\\":\\\"context\\\","
                + "\\\"opaqueResultPayload\\\":\\\"null\\\","
                + "\\\"outcomeCode\\\":\\\"200\\\"}\"}";
        assertEquals(
                resultJson,
                codec.encodeWorkerConnectionMessage(resultMessage)
        );
        assertEquals(
                resultMessage,
                codec.decodeWorkerConnectionMessage(resultJson)
        );
        assertFalse(resultMessage.toString().contains(
                resultMessage.payload()
        ));
        assertEquals(result, codec.decodeSeedResult(
                resultMessage.payload()
        ));
    }

    @Test
    void workerConnectionMessageRejectsOldFlatAndMalformedFrames() {
        assertNull(codec.decodeWorkerConnectionMessage(
                codec.encodeWorkerCommand(new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        123_456,
                        "opaque-command-item"
                ))
        ));
        assertNull(codec.decodeWorkerConnectionMessage(
                "{\"messageType\":\"TASK_ITEM_COMMAND\",\"payload\":\"x\","
                        + "\"extra\":true}"
        ));
        assertNull(codec.decodeWorkerConnectionMessage(
                "{\"messageType\":\"TASK_ITEM_COMMAND\",\"payload\":\"\"}"
        ));
        WorkerConnectionMessage unknown =
                codec.decodeWorkerConnectionMessage(
                        "{\"messageType\":\"UNKNOWN\",\"payload\":\"x\"}"
                );
        assertEquals("UNKNOWN", unknown.messageType());
    }

    @Test
    void outcomeAndRecordValidationRemainStrict() {
        assertEquals(
                SeedResultOutcomeClass.SUCCESS,
                WorkerDeliveryProtocol.classifyOutcomeCode("200")
        );
        assertEquals(
                SeedResultOutcomeClass.WORKER_FAILURE,
                WorkerDeliveryProtocol.classifyOutcomeCode("1500")
        );
        assertEquals(
                SeedResultOutcomeClass.ADAPTER_REJECTION,
                WorkerDeliveryProtocol.classifyOutcomeCode("3001")
        );
        assertNull(WorkerDeliveryProtocol.classifyOutcomeCode("500"));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkerCommandEnvelope(
                        COMMAND_ID.toUpperCase(),
                        WorkerMessageType.TASK_ITEM,
                        1,
                        "item"
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new SeedResult(COMMAND_ID, "context", "200", null)
        );
    }
}
