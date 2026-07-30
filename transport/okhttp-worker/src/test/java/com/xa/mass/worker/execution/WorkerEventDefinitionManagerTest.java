package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerEventDefinitionManagerTest {

    @Test
    void managerCopiesDefinitionsAndOnlyResolvesIdentity() {
        WorkerEventDefinition<String> definition = definition(
                "TASK",
                "test.observe",
                "\"task\""
        );
        List<WorkerEventDefinition<?>> definitions =
                new ArrayList<>();
        definitions.add(definition);
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(definitions);
        definitions.clear();

        assertSame(
                definition,
                manager.require("TASK", "test.observe")
        );
    }

    @Test
    void sameEventCodeIsIsolatedBySource() {
        WorkerEventDefinition<String> task = definition(
                "TASK",
                "shared.inspect",
                "\"task\""
        );
        WorkerEventDefinition<String> system = definition(
                "SYSTEM",
                "shared.inspect",
                "\"system\""
        );
        WorkerEventDefinitionManager manager =
                new WorkerEventDefinitionManager(
                        List.of(task, system)
                );

        assertSame(
                task,
                manager.require("TASK", "shared.inspect")
        );
        assertSame(
                system,
                manager.require("SYSTEM", "shared.inspect")
        );
    }

    @Test
    void managerRejectsInvalidAndDuplicateIdentities() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        definition(
                                "UNKNOWN",
                                "test.observe",
                                "\"unknown\""
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        definition(
                                "WORKER",
                                "test.observe",
                                "\"worker\""
                        )
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventDefinition.of(
                        "TASK",
                        "",
                        WorkerEventParameterResolvers.string(),
                        payload -> payload
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerEventDefinitionManager(List.of(
                        definition(
                                "TASK",
                                "test.observe",
                                "\"first\""
                        ),
                        definition(
                                "TASK",
                                "test.observe",
                                "\"second\""
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
                () -> manager.require("TASK", "unknown")
        );

        assertEquals(WorkerErrorCode.EVENT_NOT_FOUND, unknown.errorCode());
        assertEquals("event.require", unknown.operation());
    }

    private static WorkerEventDefinition<String> definition(
            String src,
            String eventCode,
            String result
    ) {
        return WorkerEventDefinition.of(
                src,
                eventCode,
                WorkerEventParameterResolvers.string(),
                payload -> result
        );
    }
}
