package com.xa.mass.integration.workerdynamicmatching;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DynamicMatchingWitnessTest {
    @TempDir Path temporary;

    @Test void observesWitnessResultsWithoutWaitingForTheBackgroundReader() throws Exception {
        try (var fixture = new Fixture(temporary, true)) {
            fixture.allow(Duration.ofSeconds(2));
            fixture.succeed();
            assertEquals(Set.copyOf(fixture.tokens), fixture.results());
            assertEquals(List.of(fixture.tokens), fixture.requests);
        }
    }

    @Test void successfulResultsStillRequireCompletedExecutionEvidence() throws Exception {
        try (var fixture = new Fixture(temporary, false)) {
            fixture.allow(Duration.ofSeconds(1));
            var failure = assertThrows(ProofApi.ProofFailure.class, fixture::succeed);
            assertEquals("witness-isolated-deadline", failure.code);
            assertEquals(Set.copyOf(fixture.tokens), fixture.results());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test void lateSuccessfulReadCannotExtendTheWitnessDeadline() throws Exception {
        try (var fixture = new Fixture(temporary, true)) {
            fixture.replyAfterDeadline = true;
            fixture.allow(Duration.ofSeconds(1));
            var failure = assertThrows(ProofApi.ProofFailure.class, fixture::succeed);
            assertEquals("witness-isolated-deadline", failure.code);
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test void expiredWitnessDoesNotStartAnotherRead() throws Exception {
        try (var fixture = new Fixture(temporary, true)) {
            fixture.allow(Duration.ofMillis(-1));
            var failure = assertThrows(ProofApi.ProofFailure.class, fixture::succeed);
            assertEquals("witness-isolated-deadline", failure.code);
            assertTrue(fixture.requests.isEmpty());
        }
    }

    /** Exercise the actual private wait without opening a production test API or launching background readers. */
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final Object harness, task;
        final Method wait;
        final List<String> tokens = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "token-" + i).toList();
        final List<List<Object>> requests = new CopyOnWriteArrayList<>();
        volatile boolean replyAfterDeadline;
        volatile long deadline;

        Fixture(Path directory, boolean completed) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/tasks/witness/results:load", exchange -> {
                var ids = Jsons.parseArray(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(ids);
                if (replyAfterDeadline) {
                    long remaining = deadline + TimeUnit.MILLISECONDS.toNanos(50) - System.nanoTime();
                    try { if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                var results = new LinkedHashMap<String, Object>();
                for (Object id : ids) results.put((String) id, Map.of("status", "succeeded", "opaqueResultPayload", "null"));
                byte[] body = Jsons.toJson(results).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var workers = new ArrayList<Map<String, Object>>();
            for (String group : List.of(DynamicMatchingMain.STRING, DynamicMatchingMain.PHONE)) {
                for (int i = 0; i < 500; i++) workers.add(Map.of("group", group, "key", "worker-" + i,
                        "properties", Map.of("proofTarget", group.equals(DynamicMatchingMain.STRING) && i < 100 ? "yes" : "no")));
            }
            Path spec = directory.resolve("spec.json");
            Files.writeString(spec, Jsons.toJson(Map.of("workers", workers)));
            var constructor = DynamicMatchingMain.class.getDeclaredConstructor(Map.class);
            constructor.setAccessible(true);
            harness = constructor.newInstance(Map.of("server", url, "lab", url, "spec", spec.toString(),
                    "output", directory.resolve("evidence.json").toString()));
            Class<?> type = Arrays.stream(DynamicMatchingMain.class.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals("Task")).findFirst().orElseThrow();
            var taskConstructor = type.getDeclaredConstructor(String.class, String.class, String.class, boolean.class, Set.class, boolean.class);
            taskConstructor.setAccessible(true);
            task = taskConstructor.newInstance("isolated", "witness", DynamicMatchingMain.STRING, true, Set.of(), true);
            collection(task, "tokens").addAll(tokens);
            if (completed) collection(task, "completed").addAll(tokens);
            set(task, "approved", true);
            wait = DynamicMatchingMain.class.getDeclaredMethod("succeeded", type);
            wait.setAccessible(true);
        }

        void allow(Duration remaining) throws Exception {
            deadline = System.nanoTime() + remaining.toNanos();
            set(task, "eligibleSince", deadline - TimeUnit.SECONDS.toNanos(60));
        }

        void succeed() throws Exception {
            try { wait.invoke(harness, task); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception error) throw error;
                throw failure;
            }
        }

        Collection<String> results() throws Exception { return collection(task, "succeeded"); }

        @Override public void close() throws Exception {
            ((ExecutorService) get(harness, "readers")).shutdownNow();
            ((ProofApi) get(harness, "runtime")).close();
            ((ProofApi) get(harness, "lab")).close();
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> collection(Object instance, String name) throws Exception {
        return (Collection<String>) get(instance, name);
    }
    private static Object get(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
