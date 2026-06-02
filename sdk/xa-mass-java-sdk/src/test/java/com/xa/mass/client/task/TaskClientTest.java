package com.xa.mass.client.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.http.MassHttpStreamResponse;
import com.xa.mass.contract.task.TaskContract;
import com.xa.mass.contract.task.TaskCreateRequest;
import com.xa.mass.contract.task.TaskItemBatch;
import com.xa.mass.contract.task.TaskItemSyncRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void taskCreateBuilderWritesTypedRoutingHelpersIntoSharedConfig() {
        TaskCreateRequest request = TaskCreateRequest.builder()
                .workerGroupId("phone-device-probe")
                .targetWorkerAttribute("fingerprintProfile", "fp-sg-alpha")
                .targetWorkerAttribute("region", "sg")
                .routingCode("probe.sg")
                .routeAttribute("region", "sg")
                .build();

        assertEquals("phone-device-probe", request.sharedConfig().get("workerGroupId"));
        assertEquals(Map.of(
                "fingerprintProfile", "fp-sg-alpha",
                "region", "sg"
        ), request.sharedConfig().get("targetWorkerAttributes"));
        assertEquals("probe.sg", request.sharedConfig().get("routingCode"));
        assertEquals(Map.of("region", "sg"), request.sharedConfig().get("routeAttributes"));
        assertFalse(request.sharedConfig().containsKey("workerGroupIds"));
    }

    @Test
    void taskCreateBuilderKeepsWorkerGroupSelectorsMutuallyExclusive() {
        TaskCreateRequest multipleGroups = TaskCreateRequest.builder()
                .workerGroupId("old-group")
                .workerGroupIds(Arrays.asList("pool-east", null, " ", "pool-west"))
                .build();

        assertEquals(List.of("pool-east", "pool-west"), multipleGroups.sharedConfig().get("workerGroupIds"));
        assertFalse(multipleGroups.sharedConfig().containsKey("workerGroupId"));

        TaskCreateRequest singleGroup = TaskCreateRequest.builder()
                .workerGroupIds(List.of("pool-east", "pool-west"))
                .workerGroupId("phone-device-probe")
                .build();

        assertEquals("phone-device-probe", singleGroup.sharedConfig().get("workerGroupId"));
        assertFalse(singleGroup.sharedConfig().containsKey("workerGroupIds"));
    }

    @Test
    void taskCreateBuilderRespectsRawAndTypedSharedConfigCallOrder() {
        TaskCreateRequest rawThenTyped = TaskCreateRequest.builder()
                .sharedConfig(Map.of(
                        "workerGroupId", "raw-group",
                        "targetWorkerAttributes", Map.of("region", "old")
                ))
                .workerGroupId("typed-group")
                .targetWorkerAttribute("fingerprintProfile", "fp-sg-alpha")
                .build();

        assertEquals("typed-group", rawThenTyped.sharedConfig().get("workerGroupId"));
        assertEquals(Map.of(
                "region", "old",
                "fingerprintProfile", "fp-sg-alpha"
        ), rawThenTyped.sharedConfig().get("targetWorkerAttributes"));

        TaskCreateRequest typedThenRaw = TaskCreateRequest.builder()
                .workerGroupId("typed-group")
                .targetWorkerAttributes(Map.of("fingerprintProfile", "fp-sg-alpha"))
                .sharedConfig(Map.of("workerGroupIds", List.of("raw-east", "raw-west")))
                .build();

        assertEquals(List.of("raw-east", "raw-west"), typedThenRaw.sharedConfig().get("workerGroupIds"));
        assertFalse(typedThenRaw.sharedConfig().containsKey("workerGroupId"));
        assertFalse(typedThenRaw.sharedConfig().containsKey("targetWorkerAttributes"));
    }

    @Test
    void taskCreateBuilderDefinesBlankAndNullSelectorSemantics() {
        TaskCreateRequest request = TaskCreateRequest.builder()
                .workerGroupId("phone-device-probe")
                .workerGroupId(" ")
                .workerGroupIds(List.of("pool-east"))
                .workerGroupIds(Arrays.asList(" ", null))
                .targetWorkerAttribute("fingerprintProfile", "fp-sg-alpha")
                .targetWorkerAttribute("fingerprintProfile", null)
                .routeAttribute("region", "sg")
                .routeAttribute("region", "")
                .routingCode("probe.sg")
                .routingCode(null)
                .build();

        assertFalse(request.sharedConfig().containsKey("workerGroupId"));
        assertFalse(request.sharedConfig().containsKey("workerGroupIds"));
        assertFalse(request.sharedConfig().containsKey("targetWorkerAttributes"));
        assertFalse(request.sharedConfig().containsKey("routeAttributes"));
        assertFalse(request.sharedConfig().containsKey("routingCode"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCreateRequest.builder().targetWorkerAttribute(" ", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCreateRequest.builder().routeAttribute(null, "value"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCreateRequest.builder().routeAttributes(Map.of(" ", "value")));
    }

    @Test
    void taskMainlineKeepsShellCreateItemAppendCommandAndResultReadExplicit() throws Exception {
        List<String> observed = new ArrayList<>();
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            observed.add(method + " " + path + (query == null ? "" : "?" + query));
            String body = readBody(exchange);
            if ("POST".equals(method) && "/api/v1/tasks".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("crawlerApp", request.get("project").asText());
                assertEquals("BATCH", request.get("contract").asText());
                assertTrue(!request.has("eventCode"), "eventCode must not be task shell truth");
                assertEquals("crawler-workers", request.get("sharedConfig").get("workerGroupId").asText());
                assertEquals("us", request.get("sharedConfig").get("routingCode").asText());
                assertEquals("us", request.get("sharedConfig").get("routeAttributes").get("region").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","taskName":"crawlerApp-BATCH-task-1","project":"crawlerApp","userId":"agent","contract":"BATCH","intakeStatus":"OPEN","task":{"taskId":"task-1","project":"crawlerApp","status":"NEW","unknownServerField":"ignored"},"message":"Task shell created"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/api/v1/tasks/task-1/items".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("crawler.fetch-page", request.get("eventCode").asText());
                assertEquals("https://example.com", request.get("items").get(0).get("url").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","added":1,"status":"READY","intakeStatus":"OPEN","message":"Items appended"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/api/v1/tasks/task-1/commands".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("SEAL", request.get("command").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","command":"SEAL","accepted":true,"status":"READY","intakeStatus":"SEALED"}}
                        """);
                return;
            }
            if ("GET".equals(method) && "/api/v1/tasks/task-1/results".equals(path)) {
                assertEquals("afterSeq=0&limit=10", query);
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"mode":"LIVE","taskId":"task-1","taskTerminal":false,"archiveReady":false,"items":[{"seq":1,"messageId":"msg-1","eventCode":"crawler.fetch-page","status":"SUCCESS","output":{"title":"Example"}}],"nextAfterSeq":1,"hasMore":false}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        TaskClient tasks = platform().tasks();
        TaskCreateResult created = tasks.create(TaskCreateRequest.builder()
                .project("crawlerApp")
                .userId("agent")
                .contract(TaskContract.BATCH)
                .workerGroupId("crawler-workers")
                .routingCode("us")
                .routeAttribute("region", "us")
                .build());
        TaskAppendResult appended = tasks.appendItems(created.taskId(), TaskItemBatch.builder()
                .eventCode("crawler.fetch-page")
                .item(Map.of("url", "https://example.com"))
                .build());
        TaskCommandResult command = tasks.seal(created.taskId());
        TaskResultWindow resultWindow = tasks.results(created.taskId(), TaskResultReadRequest.builder()
                .afterSeq(0L)
                .limit(10)
                .build());

        assertEquals("task-1", created.taskId());
        assertEquals(1, appended.added());
        assertTrue(command.accepted());
        assertEquals("Example", resultWindow.items().getFirst().output().get("title"));
        assertEquals(List.of(
                "POST /api/v1/tasks",
                "POST /api/v1/tasks/task-1/items",
                "POST /api/v1/tasks/task-1/commands",
                "GET /api/v1/tasks/task-1/results?afterSeq=0&limit=10"
        ), observed);
    }

    @Test
    void taskListGetUpdateSyncAndArchiveManifestUseDocumentedRoutes() throws Exception {
        List<String> observed = new ArrayList<>();
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            observed.add(method + " " + path + (query == null ? "" : "?" + query));
            if ("GET".equals(method) && "/api/v1/tasks".equals(path)) {
                assertEquals("project=crawlerApp&limit=5", query);
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"items":[{"taskId":"task-1","project":"crawlerApp"}],"total":1}}
                        """);
                return;
            }
            if ("GET".equals(method) && "/api/v1/tasks/task-1".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"task":{"taskId":"task-1","status":"NEW"},"security":{"owner":"agent"}}}
                        """);
                return;
            }
            if ("PATCH".equals(method) && "/api/v1/tasks/task-1".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","status":"NEW","intakeStatus":"OPEN","message":"Task updated"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/api/v1/tasks/task-1/items:sync".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","messageId":"msg-1","synced":true,"timedOut":false,"timeoutMs":5000,"status":"SUCCESS","output":{"ok":true}}}
                        """);
                return;
            }
            if ("GET".equals(method) && "/api/v1/tasks/task-1/results/archive".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"ready":true,"taskId":"task-1","format":"ndjson","contentType":"application/x-ndjson","contentEncoding":"gzip","itemCount":1,"byteSize":42,"checksum":"sha256:x","downloadUrl":"/api/v1/tasks/task-1/results/archive/content"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        TaskClient tasks = platform().tasks();
        TaskListResult list = tasks.list(TaskListRequest.builder().project("crawlerApp").limit(5).build());
        TaskGetResult detail = tasks.get("task-1");
        TaskUpdateResult update = tasks.update("task-1", TaskUpdateRequest.builder().project("crawlerApp").build());
        TaskSyncAppendResult sync = tasks.appendItemSync("task-1", TaskItemSyncRequest.builder()
                .eventCode("crawler.fetch-page")
                .item(Map.of("url", "https://example.com"))
                .timeoutMs(5000L)
                .build());
        TaskResultArchive archive = tasks.archive("task-1");

        assertEquals(1, list.total());
        assertEquals("agent", detail.security().get("owner"));
        assertEquals("Task updated", update.message());
        assertTrue(sync.synced());
        assertEquals("gzip", archive.contentEncoding());
        assertEquals(List.of(
                "GET /api/v1/tasks?project=crawlerApp&limit=5",
                "GET /api/v1/tasks/task-1",
                "PATCH /api/v1/tasks/task-1",
                "POST /api/v1/tasks/task-1/items:sync",
                "GET /api/v1/tasks/task-1/results/archive"
        ), observed);
    }

    @Test
    void taskHandleDelegatesToExistingTaskScopedRoutesWithoutCreatingShells() throws Exception {
        List<String> observed = new ArrayList<>();
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            observed.add(method + " " + path + (query == null ? "" : "?" + query));
            String body = readBody(exchange);
            if ("POST".equals(method) && "/api/v1/tasks/task-1/commands".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("APPROVE", request.get("command").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","command":"APPROVE","accepted":true,"status":"READY","intakeStatus":"OPEN"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/api/v1/tasks/task-1/items".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("probe.phone.metadata", request.get("eventCode").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","added":1,"status":"READY","intakeStatus":"OPEN","message":"Items appended"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/api/v1/tasks/task-1/items:sync".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("probe.phone.metadata", request.get("eventCode").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"taskId":"task-1","messageId":"msg-1","synced":true,"timedOut":false,"timeoutMs":5000,"status":"SUCCESS","output":{"ok":true}}}
                        """);
                return;
            }
            if ("GET".equals(method) && "/api/v1/tasks/task-1/results".equals(path)) {
                assertEquals("limit=1", query);
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"mode":"LIVE","taskId":"task-1","taskTerminal":false,"archiveReady":false,"items":[{"seq":1,"messageId":"msg-1","eventCode":"probe.phone.metadata","status":"SUCCESS","output":{"ok":true}}],"nextAfterSeq":1,"hasMore":false}}
                        """);
                return;
            }
            if ("GET".equals(method) && "/api/v1/tasks/task-1/results/archive".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"ready":true,"taskId":"task-1","format":"ndjson","contentType":"application/x-ndjson","itemCount":1,"byteSize":42,"checksum":"sha256:x","downloadUrl":"/api/v1/tasks/task-1/results/archive/content"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        TaskHandle handle = platform().tasks().forTask("task-1");
        TaskCommandResult approve = handle.approve();
        TaskAppendResult appended = handle.appendItems(TaskItemBatch.builder()
                .eventCode("probe.phone.metadata")
                .item(Map.of("phone", "+14155550100"))
                .build());
        TaskSyncAppendResult sync = handle.appendItemSync(TaskItemSyncRequest.builder()
                .eventCode("probe.phone.metadata")
                .item(Map.of("phone", "+14155550100"))
                .timeoutMs(5000L)
                .build());
        TaskResultWindow results = handle.results(TaskResultReadRequest.builder().limit(1).build());
        TaskResultArchive archive = handle.archive();

        assertEquals("task-1", handle.taskId());
        assertTrue(approve.accepted());
        assertEquals(1, appended.added());
        assertTrue(sync.synced());
        assertEquals("SUCCESS", results.items().getFirst().status());
        assertTrue(archive.ready());
        assertEquals(List.of(
                "POST /api/v1/tasks/task-1/commands",
                "POST /api/v1/tasks/task-1/items",
                "POST /api/v1/tasks/task-1/items:sync",
                "GET /api/v1/tasks/task-1/results?limit=1",
                "GET /api/v1/tasks/task-1/results/archive"
        ), observed);
        assertThrows(IllegalArgumentException.class, () -> platform().tasks().forTask(" "));
    }

    @Test
    void archiveContentDownloadUsesRawStreamAndPreservesMetadata() throws Exception {
        startServer(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())
                    && "/api/v1/tasks/task-1/results/archive/content".equals(exchange.getRequestURI().getRawPath())) {
                byte[] bytes = "raw-archive".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"task-1.ndjson.gz\"");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(bytes);
                }
                return;
            }
            respond(exchange, 404, "unexpected");
        });

        try (MassHttpStreamResponse response = platform().tasks().downloadArchive("task-1")) {
            assertEquals("application/x-ndjson", response.contentType());
            assertEquals("gzip", response.contentEncoding());
            assertEquals("attachment; filename=\"task-1.ndjson.gz\"", response.contentDisposition());
            assertEquals("raw-archive", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private MassPlatform platform() {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("mass_sk_task")
                .build();
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
