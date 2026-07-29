package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEventDefinitionManagerTest {

    @Test
    void managerCopiesDefinitionsAndDispatchesByStringKey()
            throws Exception {
        Map<
                String,
                WorkerEventDefinition<
                        ?,
                        ? extends Map<String, Object>
                >
        > definitions = new LinkedHashMap<>();
        definitions.put(
                "test.observe",
                WorkerEventDefinition.map(parameters -> Map.of(
                        "observed",
                        parameters.get("value")
                ))
        );
        WorkerEventDefinitionManager<Map<String, Object>> manager =
                new WorkerEventDefinitionManager<>(definitions);
        definitions.clear();

        assertEquals(
                Map.of("observed", "input"),
                manager.dispatch(
                        "test.observe",
                        Map.of("value", "input")
                )
        );
    }

    @Test
    void typedDefinitionKeepsResolverAndHandlerTypesPaired()
            throws Exception {
        WorkerEventDefinition<
                Parameters,
                Map<String, Object>
        > definition = WorkerEventDefinition.of(
                parameters -> new Parameters(
                        (String) parameters.get("value")
                ),
                parameters -> Map.of(
                        "observed",
                        parameters.value()
                )
        );
        WorkerEventDefinitionManager<Map<String, Object>> manager =
                new WorkerEventDefinitionManager<>(Map.of(
                        "test.observe",
                        definition
                ));

        assertEquals(
                Map.of("observed", "typed"),
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
                () -> new WorkerEventDefinitionManager<>(
                        Map.of("", WorkerEventDefinition.map(
                                parameters -> Map.of()
                        ))
                )
        );

        Map<
                String,
                WorkerEventDefinition<?, ? extends Object>
        > definitions = new LinkedHashMap<>();
        definitions.put("test.observe", null);
        assertThrows(
                NullPointerException.class,
                () -> new WorkerEventDefinitionManager<>(definitions)
        );

        WorkerEventDefinitionManager<Map<String, Object>> manager =
                new WorkerEventDefinitionManager<>(Map.of());
        assertThrows(
                UnknownWorkerEventException.class,
                () -> manager.dispatch("unknown", Map.of())
        );
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
