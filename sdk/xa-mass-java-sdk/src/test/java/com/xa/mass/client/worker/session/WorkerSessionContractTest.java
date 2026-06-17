package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.worker.WorkerClient;
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

class WorkerSessionContractTest {
    @Test
    void workerSessionExposesOnlyTheNarrowLifecycleContract() {
        Set<String> methodNames = Arrays.stream(WorkerSession.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "transportHint", "start", "isRunning", "close"),
                methodNames);
        assertEquals(String.class, declaredMethod("workerId").getReturnType());
        assertEquals(String.class, declaredMethod("workerGroupId").getReturnType());
        assertEquals(String.class, declaredMethod("transportHint").getReturnType());
        assertEquals(WorkerSession.class, declaredMethod("start").getReturnType());
        assertEquals(boolean.class, declaredMethod("isRunning").getReturnType());
        assertEquals(void.class, declaredMethod("close").getReturnType());
        assertEquals(0, declaredMethod("close").getExceptionTypes().length);
    }

    @Test
    void pollingSessionImplementsWorkerSessionWithRegistrationTransportHint() {
        PollingWorkerSession session = PollingWorkerSession.builder(workerClient())
                .workerId("poll-worker")
                .workerGroupId("probe-workers")
                .buildUnstarted();

        assertInstanceOf(WorkerSession.class, session);
        assertEquals("poll-worker", session.workerId());
        assertEquals("probe-workers", session.workerGroupId());
        assertEquals("polling", session.transportHint());
    }

    @Test
    void webSocketSessionImplementsWorkerSessionWithRegistrationTransportHint() {
        WebSocketWorkerSession session = WebSocketWorkerSession.builder(workerClient())
                .workerId("ws-worker")
                .workerGroupId("probe-workers")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .buildUnstarted();

        assertInstanceOf(WorkerSession.class, session);
        assertEquals("ws-worker", session.workerId());
        assertEquals("probe-workers", session.workerGroupId());
        assertEquals("realtime", session.transportHint());
    }

    private static Method declaredMethod(String name) {
        return Arrays.stream(WorkerSession.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
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
