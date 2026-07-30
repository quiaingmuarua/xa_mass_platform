package com.xa.mass.worker.execution;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerCommandDispatcherTest {

    private static final long NOW = 1_000_000L;
    private static final String MESSAGE_ID =
            "91bc4b8c-29d8-4c18-950d-72c8f25e20e0";
    private static final WorkerDeliveryCodec CODEC =
            new WorkerDeliveryCodec();

    @Test
    void successfulEventProducesCorrelatedWorkerResult() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> "{\"observed\":\""
                                + parameters.get("value")
                                + "\"}"
                )
        ));

        WorkerResult result = dispatcher.execute(encodedCommand(
                TASK,
                "test.observe",
                "{\"value\":\"ready\"}",
                NOW + 1
        )).orElseThrow();

        assertEquals(MESSAGE_ID, result.messageId());
        assertEquals(TASK, result.dst());
        assertEquals("test.observe", result.messageType());
        assertEquals("200", result.outcomeCode());
        assertEquals("{\"observed\":\"ready\"}", result.payload());
        assertEquals("result-context", result.forward());
    }

    @Test
    void sameEventCodeDispatchesBySource() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                definition("TASK", "shared.inspect", "\"task\""),
                definition("SYSTEM", "shared.inspect", "\"system\""),
                definition("ADAPTER", "shared.inspect", "\"adapter\"")
        ));

        assertEquals(
                "\"task\"",
                resultPayload(dispatcher, TASK, "shared.inspect")
        );
        assertEquals(
                "\"system\"",
                resultPayload(dispatcher, SYSTEM, "shared.inspect")
        );
        assertEquals(
                "\"adapter\"",
                resultPayload(dispatcher, ADAPTER, "shared.inspect")
        );
    }

    @Test
    void malformedPayloadAndResolverInputMapTo1400() {
        WorkerCommandDispatcher malformed = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> "\"unused\""
                )
        ));
        WorkerCommandDispatcher rejected = dispatcher(List.of(
                WorkerEventDefinition.of(
                        "TASK",
                        "test.observe",
                        parameters -> {
                            throw new WorkerException(
                                    WorkerErrorCode.EVENT_INPUT_INVALID,
                                    "event.resolve",
                                    null,
                                    null
                            );
                        },
                        ignored -> "\"unused\""
                )
        ));

        assertFailure(
                malformed.execute(encodedCommand(
                        TASK,
                        "test.observe",
                        "not-json",
                        NOW + 1
                )),
                "1400"
        );
        assertFailure(
                rejected.execute(encodedCommand(
                        TASK,
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1400"
        );
    }

    @Test
    void unknownSourceEventPairMapsTo1404() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                definition("TASK", "shared.inspect", "\"task\"")
        ));

        assertFailure(
                dispatcher.execute(encodedCommand(
                        SYSTEM,
                        "shared.inspect",
                        "{}",
                        NOW + 1
                )),
                "1404"
        );
    }

    @Test
    void handlerFailureOrEmptyPayloadMapsTo1500() {
        WorkerCommandDispatcher throwing = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> {
                            throw new IllegalStateException("failed");
                        }
                )
        ));
        WorkerCommandDispatcher empty = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> ""
                )
        ));

        assertFailure(
                throwing.execute(encodedCommand(
                        TASK,
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1500"
        );
        assertFailure(
                empty.execute(encodedCommand(
                        TASK,
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1500"
        );
    }

    @Test
    void malformedCommandIsAProtocolFailure() {
        WorkerException failure = assertThrows(
                WorkerException.class,
                () -> dispatcher(List.of()).execute("{bad-json")
        );

        assertEquals(
                WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                failure.errorCode()
        );
        assertEquals("command.decode", failure.operation());
    }

    @Test
    void customResolverReceivesTheOriginalPayloadString() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                WorkerEventDefinition.of(
                        "TASK",
                        "test.raw",
                        WorkerEventParameterResolvers.string(),
                        payload -> "\"" + payload + "\""
                )
        ));

        WorkerResult result = dispatcher.execute(encodedCommand(
                TASK,
                "test.raw",
                "not-json",
                NOW + 1
        )).orElseThrow();

        assertEquals("\"not-json\"", result.payload());
        assertEquals("200", result.outcomeCode());
    }

    @Test
    void expiredCommandIsDroppedBeforeHandlerStarts() {
        AtomicBoolean executed = new AtomicBoolean();
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> {
                            executed.set(true);
                            return "\"done\"";
                        }
                )
        ));

        Optional<WorkerResult> result = dispatcher.execute(encodedCommand(
                TASK,
                "test.observe",
                "{}",
                NOW
        ));

        assertFalse(result.isPresent());
        assertFalse(executed.get());
    }

    @Test
    void deadlineIsNotRecheckedAfterHandlerStarts() {
        WorkerCommandDispatcher dispatcher = new WorkerCommandDispatcher(
                List.of(definition(
                        "TASK",
                        "test.observe",
                        "\"done\""
                )),
                CODEC,
                new java.util.function.LongSupplier() {
                    private int calls;

                    @Override
                    public long getAsLong() {
                        calls++;
                        return calls == 1 ? NOW : NOW + 10_000;
                    }
                }
        );

        assertTrue(dispatcher.execute(encodedCommand(
                TASK,
                "test.observe",
                "{}",
                NOW + 1
        )).isPresent());
    }

    private static WorkerEventDefinition<Map<String, Object>> definition(
            String src,
            String eventCode,
            String result
    ) {
        return mapDefinition(
                src,
                eventCode,
                parameters -> result
        );
    }

    private static WorkerEventDefinition<Map<String, Object>>
    mapDefinition(
            String src,
            String eventCode,
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return WorkerEventDefinition.of(
                src,
                eventCode,
                WorkerEventParameterResolvers.jsonMap(),
                handler
        );
    }

    private static WorkerCommandDispatcher dispatcher(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        return new WorkerCommandDispatcher(
                definitions,
                CODEC,
                () -> NOW
        );
    }

    private static String resultPayload(
            WorkerCommandDispatcher dispatcher,
            WorkerMessageEndpoint src,
            String eventCode
    ) {
        return dispatcher.execute(encodedCommand(
                src,
                eventCode,
                "{}",
                NOW + 1
        )).orElseThrow().payload();
    }

    private static String encodedCommand(
            WorkerMessageEndpoint src,
            String messageType,
            String payload,
            long executeBeforeMillis
    ) {
        return CODEC.encodeWorkerCommand(new WorkerCommand(
                MESSAGE_ID,
                src,
                WORKER,
                messageType,
                executeBeforeMillis,
                payload,
                "result-context"
        ));
    }

    private static void assertFailure(
            Optional<WorkerResult> result,
            String outcomeCode
    ) {
        WorkerResult failure = result.orElseThrow();
        assertEquals(outcomeCode, failure.outcomeCode());
        assertEquals("null", failure.payload());
    }
}
