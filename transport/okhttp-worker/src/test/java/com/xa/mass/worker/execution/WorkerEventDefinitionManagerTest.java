package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEventDefinitionManagerTest {

    @Test
    void managerCopiesDefinitionsAndDispatchesByStringKey()
            throws Exception {
        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        definitions.put(
                "test.observe",
                WorkerEventDefinition.map(parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.get("value")
                )))
        );
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(definitions);
        definitions.clear();

        assertEquals(
                "{\"observed\":\"input\"}",
                manager.dispatch(
                        "test.observe",
                        Map.of("value", "input")
                )
        );
    }

    @Test
    void typedDefinitionKeepsResolverAndHandlerTypesPaired()
            throws Exception {
        WorkerEventDefinition<Parameters> definition =
                WorkerEventDefinition.of(
                parameters -> new Parameters(
                        (String) parameters.get("value")
                ),
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.value()
                ))
        );
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(Map.of(
                        "test.observe",
                        definition
                ));

        assertEquals(
                "{\"observed\":\"typed\"}",
                manager.dispatch(
                        "test.observe",
                        Map.of("value", "typed")
                )
        );
    }

    @Test
    void managerRejectsInvalidRegistrationAndUnknownEvents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(
                        Map.of("", WorkerEventDefinition.map(
                                parameters -> "null"
                        ))
                )
        );

        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        definitions.put("test.observe", null);
        assertThrows(
                NullPointerException.class,
                () -> new WorkerEventDefinitionManager(definitions)
        );

        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(Map.of());
        WorkerException unknown = assertThrows(
                WorkerException.class,
                () -> manager.dispatch("unknown", Map.of())
        );
        assertEquals(WorkerErrorCode.EVENT_NOT_FOUND, unknown.errorCode());
        assertEquals("event.dispatch", unknown.operation());
    }

    private static final class Parameters {

        private final String value;

        private Parameters(String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }
    }
}
