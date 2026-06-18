package com.xa.mass.client.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.worker.handler.WorkerResult;
import com.xa.mass.client.worker.runtime.PollingWorkerRuntime;
import com.xa.mass.client.worker.runtime.WebSocketWorkerRuntime;
import com.xa.mass.client.worker.runtime.WorkerRuntimes;
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

class WorkerRuntimeDefinitionTest {
    @Test
    void definitionContainsOnlyWorkerAbilityFacts() {
        Set<String> fieldNames = Arrays.stream(WorkerRuntimeDefinition.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "attributes", "eventHandlers"), fieldNames);
    }

    @Test
    void definitionCopiesWorkerAbilityFactsAndKeepsAttributesImmutable() {
        WorkerRuntimeDefinition definition = WorkerRuntimeDefinition.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{\"ok\":true}"))
                .build();

        assertEquals("worker-1", definition.workerId());
        assertEquals("group-1", definition.workerGroupId());
        assertEquals(Map.of("region", "sg"), definition.attributes());
        assertTrue(definition.eventHandlers().containsKey("probe.phone.metadata"));
        assertEquals(Set.of("probe.phone.metadata"), definition.eventCodes());
        assertThrows(UnsupportedOperationException.class, () -> definition.attributes().put("other", "value"));
    }

    @Test
    void workerRuntimesBuildConcreteProtocolRuntimesFromOneDefinition() {
        WorkerRuntimeDefinition definition = WorkerRuntimeDefinition.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{\"ok\":true}"))
                .build();
        WorkerRuntimes runtimes = new WorkerRuntimes(workerClient());

        PollingWorkerRuntime polling = runtimes.polling(definition).buildUnstarted();
        WebSocketWorkerRuntime webSocket = runtimes.webSocket(definition)
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .buildUnstarted();

        assertEquals("worker-1", polling.workerId());
        assertEquals("group-1", polling.workerGroupId());
        assertEquals("polling", polling.transportHint());
        assertEquals("worker-1", webSocket.workerId());
        assertEquals("group-1", webSocket.workerGroupId());
        assertEquals("realtime", webSocket.transportHint());
    }

    @Test
    void workerSpecCanBeBuiltFromRuntimeDefinitionForExplicitRegistration() {
        WorkerRuntimeDefinition definition = WorkerRuntimeDefinition.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{\"ok\":true}"))
                .build();

        WorkerSpec polling = WorkerSpec.polling(definition);
        WorkerSpec realtime = WorkerSpec.realtime(definition);

        assertEquals("worker-1", polling.workerId());
        assertEquals("group-1", polling.workerGroupId());
        assertEquals("polling", polling.transportHint());
        assertEquals(Map.of("region", "sg"), polling.attributes());
        assertEquals("realtime", realtime.transportHint());
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
