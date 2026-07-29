package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
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
        SeedResult result = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(payload -> {
                    Map<String, Object> observed =
                            new LinkedHashMap<>();
                    observed.put("observed", payload.get("value"));
                    return observed;
                })
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
    void typedDefinitionResolvesParametersBeforeHandler() {
        WorkerEventDefinition<
                ObserveParameters,
                Map<String, Object>
        > definition = WorkerEventDefinition.of(
                payload -> new ObserveParameters(
                        requireString(payload, "value")
                ),
                parameters -> Map.of(
                        "observed",
                        parameters.value()
                )
        );

        Map<
                String,
                WorkerEventDefinition<
                        ObserveParameters,
                        Map<String, Object>
                >
        > definitions = Map.of(
                "test.observe",
                definition
        );

        SeedResult result = processor(definitions).process(command(
                "worker-1",
                "{\"eventCode\":\"test.observe\","
                        + "\"payload\":{\"value\":\"typed\"}}"
        )).orElseThrow();

        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"typed\"}",
                result.opaqueResultPayload()
        );
    }

    @Test
    void inputUnknownEventAndHandlerFailureHaveStableCodes() {
        WorkerCommandProcessor processor = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(payload -> {
                    throw new IllegalStateException("failed");
                })
        ));

        assertEquals(
                "1400",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":1,\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );
        assertEquals(
                "1400",
                processor.process(command(
                        "worker-1",
                        "{\"eventCode\":\"\",\"payload\":{}}"
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
                WorkerEventDefinition.map(payload -> {
                    throw eventInput("event.execute", "invalid");
                })
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
    void resolverInputFailureAndResultEncodingFailureAreClassified() {
        WorkerCommandProcessor invalidInput = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.of(
                        payload -> {
                            throw eventInput(
                                    "event.resolveParameters",
                                    "invalid"
                            );
                        },
                        parameters -> Map.of()
                )
        ));
        assertEquals(
                "1400",
                invalidInput.process(command(
                        "worker-1",
                        "{\"eventCode\":\"test.observe\",\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );

        WorkerCommandProcessor resolverFailure = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.of(
                        payload -> {
                            throw new IllegalStateException("failed");
                        },
                        parameters -> Map.of()
                )
        ));
        assertEquals(
                "1500",
                resolverFailure.process(command(
                        "worker-1",
                        "{\"eventCode\":\"test.observe\",\"payload\":{}}"
                )).orElseThrow().outcomeCode()
        );

        WorkerCommandProcessor invalidResult = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(payload -> Map.of(
                        "unsupported",
                        new Object()
                ))
        ));
        assertEquals(
                "1500",
                invalidResult.process(command(
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

        WorkerException mismatch = assertThrows(
                WorkerException.class,
                () -> processor.process(command("worker-2", "{}"))
        );
        assertEquals(
                WorkerErrorCode.WORKER_ID_MISMATCH,
                mismatch.errorCode()
        );
        assertEquals("command.verifyWorker", mismatch.operation());
        WorkerCommandEnvelope corrupt = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                100_001,
                "{bad-json"
        );
        WorkerException invalidSeed = assertThrows(
                WorkerException.class,
                () -> processor.process(corrupt)
        );
        assertEquals(
                WorkerErrorCode.DELIVER_SEED_INVALID,
                invalidSeed.errorCode()
        );
        assertEquals(
                "command.decodeDeliverSeed",
                invalidSeed.operation()
        );
    }

    private WorkerCommandProcessor processor(
            Map<
                    String,
                    ? extends WorkerEventDefinition<
                            ?,
                            ? extends Map<String, Object>
                    >
            > definitions
    ) {
        return new WorkerCommandProcessor(
                "worker-1",
                codec,
                definitions,
                () -> 100_000
        );
    }

    private static String requireString(
            Map<String, Object> parameters,
            String name
    ) {
        Object value = parameters.get(name);
        if (!(value instanceof String)) {
            throw eventInput(
                    "event.resolveParameters",
                    name + " must be a string"
            );
        }
        return (String) value;
    }

    private static WorkerException eventInput(
            String operation,
            String message
    ) {
        return new WorkerException(
                WorkerErrorCode.EVENT_INPUT_INVALID,
                operation,
                message,
                null
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

    private static final class ObserveParameters {

        private final String value;

        private ObserveParameters(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }
    }
}
