package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import org.junit.jupiter.api.Test;

class WorkerDeliveryProtocolTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

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
    void workerConnectionMessagesUseFlatStrictJson() {
        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                123_456,
                "opaque-command-item"
        );
        String commandJson = "{\"messageType\":\"TASK_ITEM_COMMAND\","
                + "\"commandId\":\"" + COMMAND_ID + "\","
                + "\"executeBeforeMillis\":123456,"
                + "\"opaqueItem\":\"opaque-command-item\"}";
        TaskItemCommandMessage commandMessage =
                new TaskItemCommandMessage(command);

        assertEquals(
                commandJson,
                codec.encodeWorkerConnectionMessage(commandMessage)
        );
        assertEquals(
                commandMessage,
                codec.decodeWorkerConnectionMessage(commandJson)
        );

        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        String resultJson = "{\"messageType\":\"TASK_ITEM_RESULT\","
                + "\"commandId\":\"" + COMMAND_ID + "\","
                + "\"opaqueResultContext\":\"context\","
                + "\"opaqueResultPayload\":\"null\","
                + "\"outcomeCode\":\"200\"}";
        TaskItemResultMessage resultMessage =
                new TaskItemResultMessage(result);

        assertEquals(
                resultJson,
                codec.encodeWorkerConnectionMessage(resultMessage)
        );
        assertEquals(
                resultMessage,
                codec.decodeWorkerConnectionMessage(resultJson)
        );
    }

    @Test
    void workerConnectionMessagesRejectOldAndUnknownShapes() {
        String oldCommand = codec.encodeWorkerCommand(
                new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        123_456,
                        "opaque-command-item"
                )
        );
        String oldResult = codec.encodeSeedResult(new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        ));

        assertNull(codec.decodeWorkerConnectionMessage(oldCommand));
        assertNull(codec.decodeWorkerConnectionMessage(oldResult));
        assertNull(codec.decodeWorkerConnectionMessage(
                "{\"messageType\":\"UNKNOWN\"}"
        ));
        assertNull(codec.decodeWorkerConnectionMessage(
                "{\"messageType\":\"TASK_ITEM_COMMAND\","
                        + "\"commandId\":\"" + COMMAND_ID + "\","
                        + "\"executeBeforeMillis\":123456,"
                        + "\"opaqueItem\":\"item\",\"extra\":true}"
        ));
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
