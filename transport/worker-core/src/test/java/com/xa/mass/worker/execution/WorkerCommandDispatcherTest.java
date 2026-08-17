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
                        extensionName("test.missing"),
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_NOT_FOUND
        );
    }

    @Test
    void definitionExtensionsAreDefensivelyCopied() {
        List<WorkerEventDefinition<?>> extensions = new ArrayList<>();
        extensions.add(definition("test.observe", "\"copied\""));
        WorkerCommandDispatcher dispatcher =
                WorkerCommandDispatcher.forWorker(extensions);

        extensions.clear();

        assertEquals(
                "\"copied\"",
                resultPayload(
                        dispatcher,
                        TASK,
                        extensionName("test.observe")
                )
        );
    }

    @Test
    void duplicateExtensionsAreRejected() {
        WorkerEventDefinition<?> definition = definition(
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
    void invalidExtensionCapabilityNamesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(
                        "platform.worker.test.observe",
                        "\"invalid\""
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> definition("Test.Observe", "\"invalid\"")
        );
    }

    @Test
    void successfulEventProducesWorkerOutcome() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                mapDefinition(
                        "test.observe",
                        parameters -> "{\"observed\":\""
                                + parameters.get("value")
                                + "\"}"
                )
        ));

        WorkerCommandOutcome result = dispatcher.execute(command(
                TASK,
                extensionName("test.observe"),
                "{\"value\":\"ready\"}",
                ACTIVE_DEADLINE
        )).orElseThrow();

        assertEquals("200", result.outcomeCode());
        assertEquals("{\"observed\":\"ready\"}", result.payload());
    }

    @Test
    void eventNameDoesNotBindCommandSource() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                definition("shared.inspect", "\"executed\"")
        ));

        assertEquals(
                "\"executed\"",
                resultPayload(
                        dispatcher,
                        TASK,
                        extensionName("shared.inspect")
                )
        );
        assertEquals(
                "\"executed\"",
                resultPayload(
                        dispatcher,
                        SYSTEM,
                        extensionName("shared.inspect")
                )
        );
        assertEquals(
                "\"executed\"",
                resultPayload(
                        dispatcher,
                        ADAPTER,
                        extensionName("shared.inspect")
                )
        );
    }

    @Test
    void malformedPayloadAndResolverFailureMapToEventInputInvalid() {
        WorkerCommandDispatcher malformed = dispatcher(List.of(
                mapDefinition(
                        "test.observe",
                        parameters -> "\"unused\""
                )
        ));
        WorkerCommandDispatcher rejected = dispatcher(List.of(
                WorkerEventDefinition.extension(
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
                        extensionName("test.observe"),
                        "not-json",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_INPUT_INVALID
        );
        assertFailure(
                rejected.execute(command(
                        TASK,
                        extensionName("test.observe"),
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_INPUT_INVALID
        );
    }

    @Test
    void unknownEventNameMapsToEventNotFoundRegardlessOfSource() {
        WorkerCommandDispatcher dispatcher = dispatcher(List.of(
                definition("shared.inspect", "\"known\"")
        ));

        assertFailure(
                dispatcher.execute(command(
                        SYSTEM,
                        extensionName("shared.missing"),
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
                        "test.observe",
                        parameters -> {
                            throw new IllegalStateException("failed");
                        }
                )
        ));
        assertFailure(
                throwing.execute(command(
                        TASK,
                        extensionName("test.observe"),
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
                        "test.empty",
                        parameters -> ""
                ),
                mapDefinition(
                        "test.null",
                        parameters -> null
                )
        ));

        assertFailure(
                empty.execute(command(
                        TASK,
                        extensionName("test.empty"),
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_RESULT_INVALID
        );
        assertFailure(
                empty.execute(command(
                        TASK,
                        extensionName("test.null"),
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_RESULT_INVALID
        );
    }

    @Test
    void explicitWorkerExceptionCodeIsPreserved() {
        WorkerCommandDispatcher resolverFailure = dispatcher(List.of(
                WorkerEventDefinition.extension(
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
                WorkerEventDefinition.extension(
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
                        extensionName("test.resolve"),
                        "{}",
                        ACTIVE_DEADLINE
                )),
                WorkerErrorCode.EVENT_NOT_FOUND
        );
        assertFailure(
                handlerFailure.execute(command(
                        TASK,
                        extensionName("test.handle"),
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
                WorkerEventDefinition.extension(
                        "test.raw",
                        WorkerEventParameterResolvers.string(),
                        payload -> "\"" + payload + "\""
                )
        ));

        WorkerCommandOutcome result = dispatcher.execute(command(
                TASK,
                extensionName("test.raw"),
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
                        "test.observe",
                        parameters -> {
                            executed.set(true);
                            return "\"done\"";
                        }
                )
        ));

        Optional<WorkerCommandOutcome> result = dispatcher.execute(command(
                TASK,
                extensionName("test.observe"),
                "{}",
                EXPIRED_DEADLINE
        ));

        assertFalse(result.isPresent());
        assertFalse(executed.get());
    }

    private static WorkerEventDefinition<Map<String, Object>> definition(
            String capabilityName,
            String result
    ) {
        return mapDefinition(
                capabilityName,
                parameters -> result
        );
    }

    private static WorkerEventDefinition<Map<String, Object>>
    mapDefinition(
            String capabilityName,
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return WorkerEventDefinition.extension(
                capabilityName,
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

    private static String extensionName(String capabilityName) {
        return "extension.worker." + capabilityName;
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
