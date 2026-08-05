package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ScenarioWorkersTest {

    @Test
    void publicAssemblyIsInertAndRejectsUnknownLocalEvent() {
        ScenarioWorkers empty = ScenarioWorkers.fromJson(
                "{}",
                URI.create("http://127.0.0.1:18082")
        );
        empty.start();
        empty.close();

        assertThatThrownBy(() -> ScenarioWorkers.fromJson(
                config("unknown.event", 1),
                URI.create("http://127.0.0.1:18082")
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("references unknown eventCode");
    }

    @Test
    void startsAllTransportsBeforeRegisteringResourcesAndClosesInReverse() {
        ScenarioWorkerResourceClient resources = mock(
                ScenarioWorkerResourceClient.class
        );
        WebSocketWorkerTransport first = connectedTransport();
        WebSocketWorkerTransport second = connectedTransport();
        List<List<WorkerEventDefinition<?>>> receivedDefinitions =
                new ArrayList<>();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                resources,
                (workerId, ignored, definitions) -> {
                    receivedDefinitions.add(definitions);
                    return workerId.endsWith("1") ? first : second;
                }
        );

        workers.start();
        workers.start();

        assertThat(receivedDefinitions).hasSize(2);
        assertThat(receivedDefinitions.get(0))
                .isSameAs(receivedDefinitions.get(1));
        InOrder startup = inOrder(first, second, resources);
        startup.verify(first).start();
        startup.verify(second).start();
        startup.verify(resources).registerWorker(
                "scenario-group",
                "worker-1",
                "adapter-1",
                Map.of("region", "local"),
                Duration.ofSeconds(10)
        );
        startup.verify(resources).updateWorkerProperties(
                "scenario-group",
                "worker-1",
                Map.of("region", "local"),
                Duration.ofSeconds(10)
        );
        startup.verify(resources).updateIndexedProperties(
                "scenario-group",
                "worker-1",
                Map.of("index.worker.region", "local"),
                Duration.ofSeconds(10)
        );
        startup.verify(resources).registerWorker(
                "scenario-group",
                "worker-2",
                "adapter-1",
                Map.of("region", "local"),
                Duration.ofSeconds(10)
        );

        workers.close();
        workers.close();

        InOrder closing = inOrder(first, second);
        closing.verify(second).close();
        closing.verify(first).close();
        verify(first, times(1)).start();
        verify(second, times(1)).start();
    }

    @Test
    void requiredResourceFailureClosesEveryStartedTransport() {
        ScenarioWorkerResourceClient resources = mock(
                ScenarioWorkerResourceClient.class
        );
        doThrow(new ScenarioWorkerAssemblyException(
                14003,
                "resourceApi.registerWorker",
                "rejected"
        )).when(resources).registerWorker(
                anyString(),
                anyString(),
                anyString(),
                any(),
                any()
        );
        WebSocketWorkerTransport first = connectedTransport();
        WebSocketWorkerTransport second = connectedTransport();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                resources,
                (workerId, ignored, definitions) ->
                        workerId.endsWith("1") ? first : second
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14003);

        InOrder closing = inOrder(first, second);
        closing.verify(second).close();
        closing.verify(first).close();
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void indexFailureIsBestEffort() {
        ScenarioWorkerResourceClient resources = mock(
                ScenarioWorkerResourceClient.class
        );
        doThrow(new IllegalStateException("index unavailable"))
                .when(resources)
                .updateIndexedProperties(
                        anyString(), anyString(), any(), any()
                );
        WebSocketWorkerTransport transport = connectedTransport();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 1),
                resources,
                (workerId, ignored, definitions) -> transport
        );

        workers.start();

        verify(resources).updateIndexedProperties(
                anyString(), anyString(), any(), any()
        );
        workers.close();
    }

    @Test
    void connectionTimeoutPreventsResourceRegistration() {
        ScenarioWorkerResourceClient resources = mock(
                ScenarioWorkerResourceClient.class
        );
        WebSocketWorkerTransport transport = mock(
                WebSocketWorkerTransport.class
        );
        ScenarioWorkerGroupConfig config = groupConfig(
                StringUtilityWorkerEvents.MD5_EVENT_CODE,
                1,
                Duration.ofMillis(1)
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(config),
                definitions(),
                resources,
                (workerId, ignored, definitions) -> transport
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14005);

        verify(resources, never()).registerWorker(
                anyString(), anyString(), anyString(), any(), any()
        );
        verify(transport).close();
    }

    @Test
    void emptyConfigurationDoesNotTouchResourcesOrFactory() {
        ScenarioWorkerResourceClient resources = mock(
                ScenarioWorkerResourceClient.class
        );
        ScenarioWorkers.WorkerFactory factory = mock(
                ScenarioWorkers.WorkerFactory.class
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(),
                definitions(),
                resources,
                factory
        );

        workers.start();
        workers.close();

        verifyNoInteractions(resources, factory);
    }

    private static ScenarioWorkers workers(
            String json,
            ScenarioWorkerResourceClient resources,
            ScenarioWorkers.WorkerFactory factory
    ) {
        return new ScenarioWorkers(
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
                resources,
                factory
        );
    }

    private static Map<String, WorkerEventDefinition<?>> definitions() {
        WorkerEventDefinition<?> definition =
                StringUtilityWorkerEvents.definitions().get(0);
        return Map.of(definition.eventCode(), definition);
    }

    private static WebSocketWorkerTransport connectedTransport() {
        WebSocketWorkerTransport worker = mock(
                WebSocketWorkerTransport.class
        );
        when(worker.isConnected()).thenReturn(true);
        return worker;
    }

    private static String config(String eventCode, int count) {
        StringBuilder workerJson = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            if (index > 1) {
                workerJson.append(',');
            }
            workerJson.append("""
                    {"workerId":"worker-%d",
                     "workerProperties":{"region":"local"},
                     "indexedPropertyUpdates":{"index.worker.region":"local"}}
                    """.formatted(index));
        }
        return """
                {
                  "scenario-group": {
                    "eventCodes":["%s"],
                    "endpointManagerId":"adapter-1",
                    "websocketUri":"ws://127.0.0.1:18083/connect",
                    "workers":[%s]
                  }
                }
                """.formatted(eventCode, workerJson);
    }

    private static ScenarioWorkerGroupConfig groupConfig(
            String eventCode,
            int count,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerGroupConfig(
                "scenario-group",
                List.of(eventCode),
                "adapter-1",
                URI.create("ws://127.0.0.1:18083/connect"),
                ScenarioWorkersJsonParser.parse(
                        config(eventCode, count)
                ).get(0).workers(),
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                connectTimeout
        );
    }
}
