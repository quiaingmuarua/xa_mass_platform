package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSessionSpecTest {
    @Test
    void specContainsOnlySharedWorkerSessionFacts() {
        Set<String> fieldNames = Arrays.stream(WorkerSessionSpec.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "attributes", "eventHandlers", "listener"), fieldNames);
    }

    @Test
    void specCopiesSharedFactsAndKeepsAttributesImmutable() {
        WorkerSessionListener listener = new WorkerSessionListener() {
        };
        WorkerSessionSpec spec = WorkerSessionSpec.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{\"ok\":true}"))
                .listener(listener)
                .build();

        assertEquals("worker-1", spec.workerId());
        assertEquals("group-1", spec.workerGroupId());
        assertEquals(Map.of("region", "sg"), spec.attributes());
        assertTrue(spec.eventHandlers().containsKey("probe.phone.metadata"));
        assertEquals(listener, spec.listener());
        assertThrows(UnsupportedOperationException.class, () -> spec.attributes().put("other", "value"));
    }

    @Test
    void workerSessionsCanBuildConcreteSessionsFromSharedSpec() {
        WorkerSessionSpec spec = WorkerSessionSpec.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{\"ok\":true}"))
                .build();
        WorkerSessions sessions = new WorkerSessions(workerClient());

        PollingWorkerSession polling = sessions.polling(spec).buildUnstarted();
        WebSocketWorkerSession webSocket = sessions.webSocket(spec)
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .buildUnstarted();

        assertEquals("worker-1", polling.workerId());
        assertEquals("group-1", polling.workerGroupId());
        assertEquals("polling", polling.transportHint());
        assertEquals("worker-1", webSocket.workerId());
        assertEquals("group-1", webSocket.workerGroupId());
        assertEquals("realtime", webSocket.transportHint());
    }

    private static WorkerClient workerClient() {
        return new WorkerClient(new MassHttpClient(
                URI.create("http://127.0.0.1:8088/"),
                HttpClient.newHttpClient(),
                new ObjectMapper().findAndRegisterModules(),
                MassHttpClient.AuthHeader.none(),
                Duration.ofSeconds(1)));
    }
}
