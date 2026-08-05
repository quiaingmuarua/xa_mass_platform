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

    private static final String WORKER_ID_1 =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String WORKER_ID_2 =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private static final URI ENDPOINT_URI =
            URI.create("ws://127.0.0.1:18083/connect");

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
    void registersAndBindsBeforeTransportAndClosesInReverse() {
        ScenarioWorkers.WorkerControl control = workerControl();
        ScenarioWorkerIndexClient indexes = acceptedIndexes();
        WebSocketWorkerTransport first = connectedTransport();
        WebSocketWorkerTransport second = connectedTransport();
        List<String> receivedWorkerIds = new ArrayList<>();
        List<URI> receivedEndpoints = new ArrayList<>();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                control,
                indexes,
                (workerId, endpointUri, ignored, definitions) -> {
                    receivedWorkerIds.add(workerId);
                    receivedEndpoints.add(endpointUri);
                    return WORKER_ID_1.equals(workerId)
                            ? first
                            : second;
                }
        );

        workers.start();
        workers.start();

        assertThat(receivedWorkerIds)
                .containsExactly(WORKER_ID_1, WORKER_ID_2);
        assertThat(receivedEndpoints)
                .containsExactly(ENDPOINT_URI, ENDPOINT_URI);
        InOrder startup = inOrder(control, first, second, indexes);
        startup.verify(control).register(
                "scenario-group",
                "client-1",
                Duration.ofSeconds(10)
        );
        startup.verify(control).bind(
                "scenario-group",
                "client-1",
                WORKER_ID_1,
                Map.of("region", "local"),
                Duration.ofSeconds(10)
        );
        startup.verify(first).start();
        startup.verify(control).register(
                "scenario-group",
                "client-2",
                Duration.ofSeconds(10)
        );
        startup.verify(control).bind(
                "scenario-group",
                "client-2",
                WORKER_ID_2,
                Map.of("region", "local"),
                Duration.ofSeconds(10)
        );
        startup.verify(second).start();
        startup.verify(indexes).updateIndexedProperties(
                "scenario-group",
                WORKER_ID_1,
                Map.of("index.worker.region", "local"),
                Duration.ofSeconds(10)
        );

        workers.close();
        workers.close();

        InOrder closing = inOrder(first, second, control);
        closing.verify(second).close();
        closing.verify(first).close();
        closing.verify(control).close();
        verify(first, times(1)).start();
        verify(second, times(1)).start();
    }

    @Test
    void controlFailureClosesAlreadyStartedTransport() {
        ScenarioWorkers.WorkerControl control = workerControl();
        doThrow(new ScenarioWorkerAssemblyException(
                14004,
                "workerIdentity.register",
                "rejected"
        )).when(control).register(
                "scenario-group",
                "client-2",
                Duration.ofSeconds(10)
        );
        WebSocketWorkerTransport first = connectedTransport();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                control,
                acceptedIndexes(),
                (workerId, endpointUri, ignored, definitions) -> first
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14004);

        verify(first).close();
        verify(control).close();
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void indexFailureIsBestEffort() {
        ScenarioWorkerIndexClient indexes = mock(
                ScenarioWorkerIndexClient.class
        );
        doThrow(new IllegalStateException("index unavailable"))
                .when(indexes)
                .updateIndexedProperties(
                        anyString(), anyString(), any(), any()
                );
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 1),
                workerControl(),
                indexes,
                (workerId, endpointUri, ignored, definitions) ->
                        connectedTransport()
        );

        workers.start();

        verify(indexes).updateIndexedProperties(
                anyString(), anyString(), any(), any()
        );
        workers.close();
    }

    @Test
    void connectionTimeoutPreventsIndexUpdate() {
        ScenarioWorkers.WorkerControl control = workerControl();
        ScenarioWorkerIndexClient indexes = acceptedIndexes();
        WebSocketWorkerTransport transport = mock(
                WebSocketWorkerTransport.class
        );
        ScenarioWorkerGroupConfig group = groupConfig(
                StringUtilityWorkerEvents.MD5_EVENT_CODE,
                1,
                Duration.ofMillis(1)
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(group),
                definitions(),
                control,
                indexes,
                (workerId, endpointUri, ignored, definitions) -> transport
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14005);

        verify(indexes, never()).updateIndexedProperties(
                anyString(), anyString(), any(), any()
        );
        verify(transport).close();
    }

    @Test
    void emptyConfigurationDoesNotCreateWorkers() {
        ScenarioWorkers.WorkerControl control = mock(
                ScenarioWorkers.WorkerControl.class
        );
        ScenarioWorkerIndexClient indexes = mock(
                ScenarioWorkerIndexClient.class
        );
        ScenarioWorkers.WorkerFactory factory = mock(
                ScenarioWorkers.WorkerFactory.class
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(),
                definitions(),
                control,
                indexes,
                factory
        );

        workers.start();
        workers.close();

        verifyNoInteractions(indexes, factory);
        verify(control).close();
    }

    private static ScenarioWorkers workers(
            String json,
            ScenarioWorkers.WorkerControl control,
            ScenarioWorkerIndexClient indexes,
            ScenarioWorkers.WorkerFactory factory
    ) {
        return new ScenarioWorkers(
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
                control,
                indexes,
                factory
        );
    }

    private static ScenarioWorkers.WorkerControl workerControl() {
        ScenarioWorkers.WorkerControl control = mock(
                ScenarioWorkers.WorkerControl.class
        );
        when(control.register(
                "scenario-group",
                "client-1",
                Duration.ofSeconds(10)
        )).thenReturn(WORKER_ID_1);
        when(control.register(
                "scenario-group",
                "client-2",
                Duration.ofSeconds(10)
        )).thenReturn(WORKER_ID_2);
        when(control.bind(
                anyString(),
                anyString(),
                anyString(),
                any(),
                any()
        )).thenReturn(ENDPOINT_URI);
        return control;
    }

    private static ScenarioWorkerIndexClient acceptedIndexes() {
        ScenarioWorkerIndexClient indexes = mock(
                ScenarioWorkerIndexClient.class
        );
        when(indexes.updateIndexedProperties(
                anyString(), anyString(), any(), any()
        )).thenReturn(Map.of(
                "index.worker.region",
                new ScenarioWorkerIndexResult("ok", null)
        ));
        return indexes;
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
                    {"clientWorkerKey":"client-%d",
                     "workerProperties":{"region":"local"},
                     "indexedPropertyUpdates":{"index.worker.region":"local"}}
                    """.formatted(index));
        }
        return """
                {
                  "scenario-group": {
                    "eventCodes":["%s"],
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
                ScenarioWorkersJsonParser.parse(
                        config(eventCode, count)
                ).get(0).workers(),
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                connectTimeout
        );
    }
}
