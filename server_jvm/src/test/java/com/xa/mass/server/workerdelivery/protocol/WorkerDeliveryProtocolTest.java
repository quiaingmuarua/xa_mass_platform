package com.xa.mass.server.workerdelivery.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkerDeliveryProtocolTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec =
            new WorkerDeliveryCodec(JsonMapper.builder().build());

    @Test
    void decodesThePythonGoldenWorkerCommandStrictly() {
        String encoded = """
                {"commandId":"a5e9e10d-f78b-469e-93ab-864b49c189c1",\
                "executeBeforeMillis":123456,"messageType":"TASK_ITEM",\
                "opaqueItem":"opaque-command-item"}\
                """;

        assertThat(codec.decodeWorkerCommand(encoded)).isEqualTo(
                new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        123_456,
                        "opaque-command-item"
                )
        );
        assertThat(codec.decodeWorkerCommand(
                encoded.replace(
                        "\"opaqueItem\"",
                        "\"unknown\""
                )
        )).isNull();
        assertThat(codec.decodeWorkerCommand(
                encoded.replace("TASK_ITEM", "UNKNOWN")
        )).isNull();
    }

    @Test
    void encodesSeedResultInThePythonGoldenShape() {
        String encoded = codec.encodeSeedResult(
                new SeedResult(COMMAND_ID, "context", "200", "null")
        );

        assertThat(encoded).isEqualTo(
                "{\"commandId\":\"" + COMMAND_ID + "\","
                        + "\"opaqueResultContext\":\"context\","
                        + "\"opaqueResultPayload\":\"null\","
                        + "\"outcomeCode\":\"200\"}"
        );
    }

    @Test
    void classifiesOnlyTheStableOutcomeFamilies() {
        assertThat(WorkerDeliveryProtocol.classifyOutcomeCode("200"))
                .isEqualTo(SeedResultOutcomeClass.SUCCESS);
        assertThat(WorkerDeliveryProtocol.classifyOutcomeCode("1500"))
                .isEqualTo(SeedResultOutcomeClass.WORKER_FAILURE);
        assertThat(WorkerDeliveryProtocol.classifyOutcomeCode("3001"))
                .isEqualTo(SeedResultOutcomeClass.ADAPTER_REJECTION);
        assertThat(WorkerDeliveryProtocol.classifyOutcomeCode("500"))
                .isNull();
        assertThat(WorkerDeliveryProtocol.classifyOutcomeCode("2xxx"))
                .isNull();
    }

    @Test
    void rejectsNonCanonicalCorrelationAndMissingSuccessPayload() {
        assertThatThrownBy(() -> new WorkerCommandEnvelope(
                COMMAND_ID.toUpperCase(),
                WorkerMessageType.TASK_ITEM,
                1,
                "item"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
