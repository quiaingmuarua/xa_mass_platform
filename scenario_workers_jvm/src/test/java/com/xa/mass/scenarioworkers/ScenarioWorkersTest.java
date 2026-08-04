package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ScenarioWorkersTest {

    @Test
    void fromJsonIsInert() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerRuntime runtime = mock(WorkerRuntime.class);
        WorkerPropertyIndexRuntime index = mock(
                WorkerPropertyIndexRuntime.class
        );

        ScenarioWorkers workers = ScenarioWorkers.fromJson(
                config("test.observe"),
                Map.of("test.observe", definition("TASK", "test.observe")),
                catalog,
                runtime,
                index
        );

        verifyNoInteractions(catalog, runtime, index);
        workers.close();
    }

    @Test
    void rejectsInvalidDefinitionMapsAndUnknownGroupEvent() {
        assertInvalidDefinitions(
                Map.of(
                        "wrong.key",
                        definition("TASK", "test.observe")
                ),
                "does not match eventCode"
        );
        assertInvalidDefinitions(
                Map.of(
                        "test.observe",
                        definition("SYSTEM", "test.observe")
                ),
                "src must be TASK"
        );
        assertInvalidDefinitions(
                Map.of("other.event", definition("TASK", "other.event")),
                "references unknown eventCode"
        );
    }

    @Test
    void startsGroupsInOrderAndClosesInReverseOrder() {
        ScenarioWorkerGroup first = mock(ScenarioWorkerGroup.class);
        ScenarioWorkerGroup second = mock(ScenarioWorkerGroup.class);
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(first, second)
        );

        workers.start();
        workers.start();
        workers.close();
        workers.close();

        InOrder order = inOrder(first, second);
        order.verify(first).start();
        order.verify(second).start();
        order.verify(second).close();
        order.verify(first).close();
        verify(first, times(1)).start();
        verify(second, times(1)).start();
    }

    @Test
    void startupFailureClosesStartingAndStartedGroups() {
        ScenarioWorkerGroup first = mock(ScenarioWorkerGroup.class);
        ScenarioWorkerGroup second = mock(ScenarioWorkerGroup.class);
        RuntimeException failure = new RuntimeException("start failed");
        doThrow(failure).when(second).start();
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(first, second)
        );

        assertThatThrownBy(workers::start).isSameAs(failure);

        InOrder order = inOrder(first, second);
        order.verify(first).start();
        order.verify(second).start();
        order.verify(second).close();
        order.verify(first).close();
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertInvalidDefinitions(
            Map<String, WorkerEventDefinition<?>> definitions,
            String message
    ) {
        assertThatThrownBy(() -> ScenarioWorkers.fromJson(
                config("test.observe"),
                definitions,
                mock(WorkerResourceCatalog.class),
                mock(WorkerRuntime.class),
                mock(WorkerPropertyIndexRuntime.class)
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining(message);
    }

    private static WorkerEventDefinition<String> definition(
            String src,
            String eventCode
    ) {
        return WorkerEventDefinition.of(
                src,
                eventCode,
                WorkerEventParameterResolvers.string(),
                value -> value
        );
    }

    private static String config(String eventCode) {
        return """
                {
                  "group": {
                    "eventCodes":["%s"],
                    "endpointManagerId":"adapter",
                    "websocketUri":"ws://127.0.0.1:18083/connect",
                    "workers":[{"workerId":"worker-1"}]
                  }
                }
                """.formatted(eventCode);
    }
}
