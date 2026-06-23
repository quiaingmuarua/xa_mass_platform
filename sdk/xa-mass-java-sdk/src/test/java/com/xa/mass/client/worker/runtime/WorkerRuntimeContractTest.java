package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WorkerRuntimeContractTest {
    @Test
    void workerRuntimeExposesOnlyTheNarrowLifecycleContract() {
        Set<String> methodNames = Arrays.stream(WorkerRuntime.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "transportHint", "reporter", "start", "isRunning", "close"),
                methodNames);
        assertEquals(String.class, declaredMethod("workerId").getReturnType());
        assertEquals(String.class, declaredMethod("workerGroupId").getReturnType());
        assertEquals(String.class, declaredMethod("transportHint").getReturnType());
        assertEquals(WorkerRuntimeReporter.class, declaredMethod("reporter").getReturnType());
        assertEquals(WorkerRuntime.class, declaredMethod("start").getReturnType());
        assertEquals(boolean.class, declaredMethod("isRunning").getReturnType());
        assertEquals(void.class, declaredMethod("close").getReturnType());
        assertEquals(0, declaredMethod("close").getExceptionTypes().length);
    }

    @Test
    void pollingRuntimeImplementsWorkerRuntimeWithRegistrationTransportHint() {
        PollingWorkerRuntime runtime = PollingWorkerRuntime.builder(workerClient(), definition("poll-worker"))
                .buildUnstarted();

        assertInstanceOf(WorkerRuntime.class, runtime);
        assertEquals("poll-worker", runtime.workerId());
        assertEquals("probe-workers", runtime.workerGroupId());
        assertEquals("polling", runtime.transportHint());
    }

    @Test
    void webSocketRuntimeImplementsWorkerRuntimeWithRegistrationTransportHint() {
        WebSocketWorkerRuntime runtime = WebSocketWorkerRuntime.builder(workerClient(), definition("ws-worker"))
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .buildUnstarted();

        assertInstanceOf(WorkerRuntime.class, runtime);
        assertEquals("ws-worker", runtime.workerId());
        assertEquals("probe-workers", runtime.workerGroupId());
        assertEquals("realtime", runtime.transportHint());
    }

    private static Method declaredMethod(String name) {
        return Arrays.stream(WorkerRuntime.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static WorkerRuntimeDefinition definition(String workerId) {
        return WorkerRuntimeDefinition.builder()
                .workerId(workerId)
                .workerGroupId("probe-workers")
                .event("probe.phone.metadata", dispatch -> WorkerActionResult.success("{}"))
                .build();
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
