package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.message.WorkerResultHandlingResult.ACCEPTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_RESULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdapterMessageDefinitionManagerTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void resolvesOnlyTheStableOuterMessageBeforeCallingTheHandler() {
        SeedResult expected = TestMessages.successResult(COMMAND_ID);
        String encodedSeedResult = codec.encodeSeedResult(expected);
        AtomicReference<String> handled = new AtomicReference<>();
        Map<
                String,
                AdapterMessageDefinition<?, WorkerResultHandlingResult>
                > definitions = new LinkedHashMap<>();
        definitions.put(
                TASK_ITEM_RESULT.name(),
                AdapterMessageDefinition.of(
                        payload -> payload,
                        (workerId, payload) -> {
                            assertThat(workerId).isEqualTo("worker-1");
                            handled.set(payload);
                            return ACCEPTED;
                        }
                )
        );
        AdapterMessageDefinitionManager<WorkerResultHandlingResult> manager =
                new AdapterMessageDefinitionManager<>(definitions);
        definitions.clear();

        assertThat(manager.dispatch(
                "worker-1",
                new WorkerConnectionMessage(
                        TASK_ITEM_RESULT.name(),
                        encodedSeedResult
                )
        )).isEqualTo(ACCEPTED);
        assertThat(handled).hasValue(encodedSeedResult);
    }

    @Test
    void rejectsInvalidDefinitionsAndUnknownMessages() {
        Map<String, AdapterMessageDefinition<?, String>> blankKey =
                new LinkedHashMap<>();
        blankKey.put(
                " ",
                AdapterMessageDefinition.of(
                        frame -> frame,
                        (workerId, frame) -> workerId
                )
        );
        Map<String, AdapterMessageDefinition<?, String>> nullDefinition =
                new LinkedHashMap<>();
        nullDefinition.put("TYPE", null);

        assertThatThrownBy(() ->
                new AdapterMessageDefinitionManager<>(blankKey)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AdapterMessageDefinitionManager<>(nullDefinition)
        ).isInstanceOf(NullPointerException.class);

        AdapterMessageDefinitionManager<WorkerResultHandlingResult> manager =
                new AdapterMessageDefinitionManager<>(Map.of(
                        TASK_ITEM_RESULT.name(),
                        AdapterMessageDefinition.of(
                                payload -> payload,
                                (workerId, payload) -> ACCEPTED
                        )
                ));

        assertThatThrownBy(() -> manager.dispatch(
                "worker-1",
                new WorkerConnectionMessage(
                        "TASK_ITEM_COMMAND",
                        "opaque"
                )
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.dispatch(
                " ",
                new WorkerConnectionMessage(
                        TASK_ITEM_RESULT.name(),
                        "opaque"
                )
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
