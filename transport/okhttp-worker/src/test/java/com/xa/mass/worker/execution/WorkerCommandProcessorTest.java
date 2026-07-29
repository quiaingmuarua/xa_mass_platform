package com.xa.mass.worker.execution;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerCommandProcessorTest {

    private static final long NOW = 1_000_000L;
    private static final String MESSAGE_ID =
            "91bc4b8c-29d8-4c18-950d-72c8f25e20e0";

    @Test
    void successfulEventProducesCorrelatedWorkerResult() {
        WorkerCommandProcessor processor = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(parameters ->
                        "{\"observed\":\""
                                + parameters.get("value")
                                + "\"}"
                )
        ));

        WorkerResult result = processor.process(command(
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
    void malformedPayloadAndResolverInputMapTo1400() {
        WorkerCommandProcessor malformed = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(parameters -> "\"unused\"")
        ));
        WorkerCommandProcessor rejected = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.of(
                        parameters -> {
                            throw new com.xa.mass.worker.error.WorkerException(
                                    com.xa.mass.worker.error.WorkerErrorCode
                                            .EVENT_INPUT_INVALID,
                                    "event.resolve",
                                    null,
                                    null
                            );
                        },
                        ignored -> "\"unused\""
                )
        ));

        assertFailure(
                malformed.process(command(
                        "test.observe",
                        "not-json",
                        NOW + 1
                )),
                "1400"
        );
        assertFailure(
                rejected.process(command(
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1400"
        );
    }

    @Test
    void unknownEventMapsTo1404() {
        assertFailure(
                processor(Map.of()).process(command(
                        "missing.event",
                        "{}",
                        NOW + 1
                )),
                "1404"
        );
    }

    @Test
    void handlerFailureOrEmptyPayloadMapsTo1500() {
        WorkerCommandProcessor throwing = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(parameters -> {
                    throw new IllegalStateException("failed");
                })
        ));
        WorkerCommandProcessor empty = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(parameters -> "")
        ));

        assertFailure(
                throwing.process(command(
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1500"
        );
        assertFailure(
                empty.process(command(
                        "test.observe",
                        "{}",
                        NOW + 1
                )),
                "1500"
        );
    }

    @Test
    void expiredCommandIsDroppedBeforeHandlerStarts() {
        AtomicBoolean executed = new AtomicBoolean();
        WorkerCommandProcessor processor = processor(Map.of(
                "test.observe",
                WorkerEventDefinition.map(parameters -> {
                    executed.set(true);
                    return "\"done\"";
                })
        ));

        Optional<WorkerResult> result = processor.process(command(
                "test.observe",
                "{}",
                NOW
        ));

        assertFalse(result.isPresent());
        assertFalse(executed.get());
    }

    @Test
    void deadlineIsNotRecheckedAfterHandlerStarts() {
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                Map.of(
                        "test.observe",
                        WorkerEventDefinition.map(parameters -> "\"done\"")
                ),
                new java.util.function.LongSupplier() {
                    private int calls;

                    @Override
                    public long getAsLong() {
                        calls++;
                        return calls == 1 ? NOW : NOW + 10_000;
                    }
                }
        );

        assertTrue(processor.process(command(
                "test.observe",
                "{}",
                NOW + 1
        )).isPresent());
    }

    private static WorkerCommandProcessor processor(
            Map<String, ? extends WorkerEventDefinition<?>> definitions
    ) {
        return new WorkerCommandProcessor(definitions, () -> NOW);
    }

    private static WorkerCommand command(
            String messageType,
            String payload,
            long executeBeforeMillis
    ) {
        return new WorkerCommand(
                MESSAGE_ID,
                TASK,
                WORKER,
                messageType,
                executeBeforeMillis,
                payload,
                "result-context"
        );
    }

    private static void assertFailure(
            Optional<WorkerResult> result,
            String outcomeCode
    ) {
        WorkerResult failure = result.orElseThrow();
        assertEquals(outcomeCode, failure.outcomeCode());
        assertEquals("null", failure.payload());
        assertEquals(TASK, failure.dst());
    }
}
