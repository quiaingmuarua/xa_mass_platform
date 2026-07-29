package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerCommandProcessorTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void temporaryHandlerProducesObservableSuccess() {
        WorkerEventHandler handler = payload -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("observed", payload.get("value"));
            return result;
        };

        SeedResult result = processor(Map.of(
                "test.observe",
                handler
        )).process(command(
                "worker-1",
                "{\"eventCode\":\"test.observe\","
                        + "\"payload\":{\"value\":\"input\"}}"
        )).orElseThrow();

        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"input\"}",
                result.opaqueResultPayload()
        );
        assertEquals("context", result.opaqueResultContext());
    }

    @Test
    void inputUnknownEventAndHandlerFailureHaveStableCodes() {
        WorkerCommandProcessor processor = processor(Map.of(
                "test.observe",
                payload -> {
                    throw new IllegalStateException("failed");
                }
        ));

        assertEquals(
                "1400",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":1,\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );
        assertEquals(
                "1404",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":\"unknown\",\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );
        assertEquals(
                "1500",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":\"test.observe\",\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );
    }

    @Test
    void workerInputFailureMapsToInputOutcome() {
        WorkerCommandProcessor processor = processor(Map.of(
                "test.observe",
                payload -> {
                    throw new WorkerInputException("invalid");
                }
        ));

        assertEquals(
                "1400",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":\"test.observe\",\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );
    }

    @Test
    void expiredCommandIsDroppedAndInvalidSeedIsProtocolFailure() {
        WorkerCommandProcessor processor = processor(Map.of());
        WorkerCommandEnvelope expired = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                100_000,
                codec.encodeDeliverSeed(new DeliverSeed(
                        "worker-1",
                        "{}",
                        "context"
                ))
        );
        assertEquals(Optional.empty(), processor.process(expired));

        assertThrows(WorkerProtocolException.class, () ->
                processor.process(command("worker-2", "{}"))
        );
        WorkerCommandEnvelope corrupt = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                100_001,
                "{bad-json"
        );
        assertThrows(
                WorkerProtocolException.class,
                () -> processor.process(corrupt)
        );
    }

    private WorkerCommandProcessor processor(
            Map<String, WorkerEventHandler> handlers
    ) {
        return new WorkerCommandProcessor(
                "worker-1",
                codec,
                handlers,
                () -> 100_000
        );
    }

    private WorkerCommandEnvelope command(
            String workerId,
            String deliveryItem
    ) {
        return new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                100_001,
                codec.encodeDeliverSeed(new DeliverSeed(
                        workerId,
                        deliveryItem,
                        "context"
                ))
        );
    }
}
