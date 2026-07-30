package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEventDefinitionManagerTest {

    @Test
    void managerCopiesDefinitionsAndDispatchesBySourceAndEvent()
            throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                new ArrayList<>();
        definitions.add(WorkerEventDefinition.map(
                "TASK",
                "test.observe",
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.get("value")
                ))
        ));
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(definitions);
        definitions.clear();

        assertEquals(
                "{\"observed\":\"input\"}",
                manager.dispatch(
                        "TASK",
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
                        "TASK",
                        "test.observe",
                        parameters -> new Parameters(
                                (String) parameters.get("value")
                        ),
                        parameters -> Jsons.toJson(Map.of(
                                "observed",
                                parameters.value()
                        ))
                );
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(List.of(definition));

        assertEquals(
                "{\"observed\":\"typed\"}",
                manager.dispatch(
                        "TASK",
                        "test.observe",
                        Map.of("value", "typed")
                )
        );
    }

    @Test
    void managerRejectsInvalidAndDuplicateIdentities() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        WorkerEventDefinition.map(
                                "UNKNOWN",
                                "test.observe",
                                parameters -> "null"
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        WorkerEventDefinition.map(
                                "WORKER",
                                "test.observe",
                                parameters -> "null"
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.map(
                        "TASK",
                        "",
                        parameters -> "null"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        WorkerEventDefinition.map(
                                "TASK",
                                "test.observe",
                                parameters -> "\"first\""
                        ),
                        WorkerEventDefinition.map(
                                "TASK",
                                "test.observe",
                                parameters -> "\"second\""
                        )
                ))
        );
    }

    @Test
    void unknownSourceEventPairUsesStableWorkerError() {
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(List.of());
        WorkerException unknown = assertThrows(
                WorkerException.class,
                () -> manager.dispatch(
                        "TASK",
                        "unknown",
                        Map.of()
                )
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
