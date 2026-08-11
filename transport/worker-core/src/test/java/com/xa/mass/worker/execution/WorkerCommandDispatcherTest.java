package com.xa.mass.worker.execution;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerCommandDispatcherTest {

    private static final long EXPIRED_DEADLINE = 1L;
    private static final long ACTIVE_DEADLINE = Long.MAX_VALUE;

    @Test
    void workerWithoutExtensionsHasNoBusinessEvents() {
        assertFailure(
                WorkerCommandDispatcher.forWorker().execute(command(
                        TASK,
                        "test.missing",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_NOT_FOUND
        );
    }

    @Test
    void definitionExtensionsAreDefensivelyCopied() {
        List<WorkerEventDefinition<?>> extensions = new ArrayList<>();
        extensions.add(definition(
                "TASK",
                "test.observe",
                "\"copied\""
        ));
        WorkerCommandDispatcher dispatcher =
                WorkerCommandDispatcher.forWorker(extensions);

        extensions.clear();

        assertEquals(
                "\"copied\"",
                resultPayload(dispatcher, TASK, "test.observe")
        );
    }

    @Test
    void duplicateExtensionsAreRejected() {
        WorkerEventDefinition<?> definition = definition(
                "TASK",
                "test.observe",
                "\"duplicate\""
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerCommandDispatcher.forWorker(List.of(
                        definition,
                        definition
                ))
        );
    }

    @Test
    void invalidDefinitionSourcesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerCommandDispatcher.forWorker(List.of(
                        definition(
                                "UNKNOWN",
                                "test.observe",
                                "\"unknown\""
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerCommandDispatcher.forWorker(List.of(
                        definition(
                                "WORKER",
                                "test.observe",
                                "\"worker\""
                        )
                ))
        );
    }

    @Test
    void successfulEventProducesWorkerOutcome() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> "{\"observed\":\""
                                + parameters.get("value")
                                + "\"}"
                )
        ));

        WorkerCommandOutcome result = dispatcher.execute(command(
                TASK,
                "test.observe",
                "{\"value\":\"ready\"}",
                ACTIVE_DEADLINE
        )).orElseThrow();

        assertEquals("200", result.outcomeCode());
        assertEquals("{\"observed\":\"ready\"}", result.payload());
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
    void malformedPayloadAndResolverFailureMapToEventInputInvalid() {
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
                malformed.execute(command(
                        TASK,
                        "test.observe",
                        "not-json",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_INPUT_INVALID
        );
        assertFailure(
                rejected.execute(command(
                        TASK,
                        "test.observe",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_INPUT_INVALID
        );
    }

    @Test
    void unknownSourceEventPairMapsToEventNotFound() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                definition("TASK", "shared.inspect", "\"task\"")
        ));

        assertFailure(
                dispatcher.execute(command(
                        SYSTEM,
                        "shared.inspect",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_NOT_FOUND
        );
    }

    @Test
    void handlerFailureMapsToEventExecutionFailed() {
        WorkerCommandDispatcher throwing = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.observe",
                        parameters -> {
                            throw new IllegalStateException("failed");
                        }
                )
        ));
        assertFailure(
                throwing.execute(command(
                        TASK,
                        "test.observe",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_EXECUTION_FAILED
        );
    }

    @Test
    void emptyOrNullHandlerResultMapsToEventResultInvalid() {
        WorkerCommandDispatcher empty = dispatcher(List.of(
                mapDefinition(
                        "TASK",
                        "test.empty",
                        parameters -> ""
                ),
                mapDefinition(
                        "TASK",
                        "test.null",
                        parameters -> null
                )
        ));

        assertFailure(
                empty.execute(command(
                        TASK,
                        "test.empty",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_RESULT_INVALID
        );
        assertFailure(
                empty.execute(command(
                        TASK,
                        "test.null",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_RESULT_INVALID
        );
    }

    @Test
    void explicitWorkerExceptionCodeIsPreserved() {
        WorkerCommandDispatcher resolverFailure = dispatcher(List.of(
                WorkerEventDefinition.of(
                        "TASK",
                        "test.resolve",
                        payload -> {
                            throw new WorkerException(
                                    WorkerErrorCode.EVENT_NOT_FOUND,
                                    "event.resolve",
                                    null,
                                    null
                            );
                        },
                        ignored -> "unused"
                )
        ));
        WorkerCommandDispatcher handlerFailure = dispatcher(List.of(
                WorkerEventDefinition.of(
                        "TASK",
                        "test.handle",
                        WorkerEventParameterResolvers.string(),
                        ignored -> {
                            throw new WorkerException(
                                    WorkerErrorCode.EVENT_RESULT_INVALID,
                                    "event.execute",
                                    null,
                                    null
                            );
                        }
                )
        ));

        assertFailure(
                resolverFailure.execute(command(
                        TASK,
                        "test.resolve",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_NOT_FOUND
        );
        assertFailure(
                handlerFailure.execute(command(
                        TASK,
                        "test.handle",
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_RESULT_INVALID
        );
    }

    @Test
    void malformedCommandIsAProtocolFailure() {
        WorkerException failure = assertThrows(
                WorkerException.class,
                () -> dispatcher(List.of()).execute(null)
        );

        assertEquals(
                WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                failure.errorCode()
        );
        assertEquals("command.execute", failure.operation());
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

        WorkerCommandOutcome result = dispatcher.execute(command(
                TASK,
                "test.raw",
                "not-json",
                ACTIVE_DEADLINE
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

        Optional<WorkerCommandOutcome> result = dispatcher.execute(command(
                TASK,
                "test.observe",
                "{}",
                EXPIRED_DEADLINE
        ));

        assertFalse(result.isPresent());
        assertFalse(executed.get());
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
        return WorkerCommandDispatcher.forWorker(definitions);
    }

    private static String resultPayload(
            WorkerCommandDispatcher dispatcher,
            DeliveryEndpoint src,
            String eventCode
    ) {
        return dispatcher.execute(command(
                src,
                eventCode,
                "{}",
                ACTIVE_DEADLINE
        )).orElseThrow().payload();
    }

    private static DeliveryCommand command(
            DeliveryEndpoint src,
            String messageType,
            String payload,
            long executeBeforeMillis
    ) {
        return DeliveryCommand.create(
                src,
                WORKER,
                messageType,
                executeBeforeMillis,
                payload,
                "result-context"
        );
    }

    private static void assertFailure(
            Optional<WorkerCommandOutcome> result,
            WorkerErrorCode errorCode
    ) {
        WorkerCommandOutcome failure = result.orElseThrow();
        assertEquals(
                Integer.toString(errorCode.code()),
                failure.outcomeCode()
        );
        assertEquals(errorCode.defaultMessage(), failure.payload());
    }
}
