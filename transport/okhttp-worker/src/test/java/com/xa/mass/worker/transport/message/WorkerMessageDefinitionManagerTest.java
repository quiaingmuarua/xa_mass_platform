package com.xa.mass.worker.transport.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerMessageDefinitionManagerTest {

    @Test
    void resolvesTheFrameBeforeCallingTheTypedHandler() {
        Map<String, WorkerMessageDefinition<?, String>> definitions =
                new LinkedHashMap<>();
        definitions.put(
                "TEXT",
                WorkerMessageDefinition.of(
                        payload -> payload,
                        String::toUpperCase
                )
        );
        WorkerMessageDefinitionManager<String> manager =
                new WorkerMessageDefinitionManager<>(definitions);
        definitions.clear();

        assertEquals(
                "READY",
                manager.dispatch(
                        new WorkerConnectionMessage("TEXT", "ready")
                )
        );
    }

    @Test
    void rejectsInvalidDefinitionsAndFrames() {
        Map<String, WorkerMessageDefinition<?, String>> blankKey =
                new LinkedHashMap<>();
        blankKey.put(
                " ",
                WorkerMessageDefinition.of(
                        frame -> frame,
                        Object::toString
                )
        );
        Map<String, WorkerMessageDefinition<?, String>> nullDefinition =
                new LinkedHashMap<>();
        nullDefinition.put("TYPE", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerMessageDefinitionManager<>(blankKey)
        );
        assertThrows(
                NullPointerException.class,
                () -> new WorkerMessageDefinitionManager<>(nullDefinition)
        );

        WorkerMessageDefinitionManager<String> manager =
                new WorkerMessageDefinitionManager<>(Map.of(
                        "TEXT",
                        WorkerMessageDefinition.of(
                                payload -> payload,
                                String::toUpperCase
                        )
                ));

        assertInvalid(
                manager,
                new WorkerConnectionMessage("UNKNOWN", "ready")
        );
        assertInvalid(manager, null);
    }

    private static void assertInvalid(
            WorkerMessageDefinitionManager<String> manager,
            WorkerConnectionMessage message
    ) {
        WorkerException error = assertThrows(
                WorkerException.class,
                () -> manager.dispatch(message)
        );
        assertEquals(
                WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                error.errorCode()
        );
    }
}
