package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCredentialBootstrapMainTest {

    @TempDir
    private Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void validCacheIsValidatedWithoutCreatingDuplicateCredential() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("cached-key", loginCalls, createCalls);
        Path cacheFile = tempDir.resolve("task-api-key.txt");
        Files.writeString(cacheFile, "cached-key\n", StandardCharsets.UTF_8);

        bootstrapper(cacheFile, true, true).prepare();

        assertThat(loginCalls).hasValue(0);
        assertThat(createCalls).hasValue(0);
        assertThat(Files.readString(cacheFile)).isEqualTo("cached-key\n");
    }

    @Test
    void staleCacheLogsInCreatesKeyAndOverwritesCache() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("different-key", loginCalls, createCalls);
        Path cacheFile = tempDir.resolve("task-api-key.txt");
        Files.writeString(cacheFile, "stale-key\n", StandardCharsets.UTF_8);

        bootstrapper(cacheFile, true, true).prepare();

        assertThat(loginCalls).hasValue(1);
        assertThat(createCalls).hasValue(1);
        assertThat(Files.readString(cacheFile).trim()).isEqualTo("new-task-key");
    }

    @Test
    void principalConflictCreatesLocalReplacementPrincipalAndOverwritesCache() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("different-key", loginCalls, createCalls, true);
        Path cacheFile = tempDir.resolve("task-api-key.txt");
        Files.writeString(cacheFile, "stale-key\n", StandardCharsets.UTF_8);

        bootstrapper(cacheFile, true, true).prepare();

        assertThat(loginCalls).hasValue(1);
        assertThat(createCalls).hasValue(2);
        assertThat(Files.readString(cacheFile).trim()).isEqualTo("new-task-key");
    }

    @Test
    void staleCacheFailsWhenRefreshDisabled() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("different-key", loginCalls, createCalls);
        Path cacheFile = tempDir.resolve("task-api-key.txt");
        Files.writeString(cacheFile, "stale-key\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> bootstrapper(cacheFile, true, false).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cached task API key is invalid");
        assertThat(loginCalls).hasValue(0);
        assertThat(createCalls).hasValue(0);
    }

    @Test
    void missingCacheFailsWhenCreateDisabled() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("unused", loginCalls, createCalls);
        Path cacheFile = tempDir.resolve("missing-task-api-key.txt");

        assertThatThrownBy(() -> bootstrapper(cacheFile, false, true).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creation is disabled");
        assertThat(loginCalls).hasValue(0);
        assertThat(createCalls).hasValue(0);
    }

    @Test
    void workerKindCreatesWorkerCredentialAndCache() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("unused", loginCalls, createCalls);
        Path cacheFile = tempDir.resolve("worker-api-key.txt");

        bootstrapper(
                ScenarioCredentialBootstrapMain.CredentialKind.WORKER,
                cacheFile,
                true,
                true).prepare();

        assertThat(loginCalls).hasValue(1);
        assertThat(createCalls).hasValue(1);
        assertThat(Files.readString(cacheFile).trim()).isEqualTo("new-worker-key");
    }

    @Test
    void envKindChecksCatalogAndCreatesTaskAndWorkerCaches() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        startServer("unused", loginCalls, createCalls);

        ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions options =
                new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions(
                        ScenarioCredentialBootstrapMain.CredentialKind.ENV,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        tempDir.resolve("task-api-key.txt"),
                        "ops-admin",
                        "ops-admin",
                        "unused-env-principal",
                        "ops-admin",
                        List.of(),
                        List.of(),
                        List.of(),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        false
                );
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper bootstrapper =
                new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper(
                        options,
                        new ObjectMapper().findAndRegisterModules(),
                        HttpClient.newBuilder().cookieHandler(cookieManager).build()
                );

        List<Path> prepared = bootstrapper.prepareEnvironment();

        assertThat(loginCalls).hasValue(1);
        assertThat(createCalls).hasValue(103);
        assertThat(prepared).hasSize(1);
        assertThat(Files.readString(prepared.get(0)).trim()).isEqualTo("new-task-key");
    }

    @Test
    void envKindRevokesStaleWorkerCredentialBeforeRecreatingBoundCredential() throws Exception {
        AtomicInteger loginCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger revokeCalls = new AtomicInteger();
        startServer("unused", loginCalls, createCalls, false, true, revokeCalls);

        ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions options =
                new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions(
                        ScenarioCredentialBootstrapMain.CredentialKind.ENV,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        tempDir.resolve("task-api-key.txt"),
                        "ops-admin",
                        "ops-admin",
                        "unused-env-principal",
                        "ops-admin",
                        List.of(),
                        List.of(),
                        List.of(),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        false
                );
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper bootstrapper =
                new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper(
                        options,
                        new ObjectMapper().findAndRegisterModules(),
                        HttpClient.newBuilder().cookieHandler(cookieManager).build()
                );

        bootstrapper.prepareEnvironment();

        assertThat(loginCalls).hasValue(1);
        assertThat(revokeCalls).hasValue(1);
        assertThat(createCalls).hasValue(103);
    }

    private ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper bootstrapper(Path cacheFile,
                                                                                       boolean createIfMissing,
                                                                                       boolean refreshStaleCache) {
        return bootstrapper(
                ScenarioCredentialBootstrapMain.CredentialKind.TASK,
                cacheFile,
                createIfMissing,
                refreshStaleCache);
    }

    private ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper bootstrapper(
            ScenarioCredentialBootstrapMain.CredentialKind kind,
            Path cacheFile,
            boolean createIfMissing,
            boolean refreshStaleCache) {
        ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions options =
                new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapOptions(
                        kind,
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        cacheFile,
                        "ops-admin",
                        "ops-admin",
                        kind == ScenarioCredentialBootstrapMain.CredentialKind.WORKER
                                ? "scenario-worker-local"
                                : "crawler-task-producer-local",
                        "ops-admin",
                        kind == ScenarioCredentialBootstrapMain.CredentialKind.WORKER ? List.of("*") : List.of("crawlerApp"),
                        kind == ScenarioCredentialBootstrapMain.CredentialKind.WORKER ? List.of("*") : List.of("crawler.fetch-page"),
                        kind.permissions(),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        createIfMissing,
                        refreshStaleCache,
                        false
                );
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return new ScenarioCredentialBootstrapMain.ScenarioCredentialBootstrapper(
                options,
                new ObjectMapper().findAndRegisterModules(),
                HttpClient.newBuilder().cookieHandler(cookieManager).build()
        );
    }

    private void startServer(String validApiKey,
                             AtomicInteger loginCalls,
                             AtomicInteger createCalls) throws IOException {
        startServer(validApiKey, loginCalls, createCalls, false);
    }

    private void startServer(String validApiKey,
                             AtomicInteger loginCalls,
                             AtomicInteger createCalls,
                             boolean rejectDefaultTaskPrincipal) throws IOException {
        startServer(validApiKey, loginCalls, createCalls, rejectDefaultTaskPrincipal, false, new AtomicInteger());
    }

    private void startServer(String validApiKey,
                             AtomicInteger loginCalls,
                             AtomicInteger createCalls,
                             boolean rejectDefaultTaskPrincipal,
                             boolean staleFirstWorkerCredential,
                             AtomicInteger revokeCalls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/api-keys:current", exchange -> {
            String apiKey = exchange.getRequestHeaders().getFirst("X-Mass-Api-Key");
            if (validApiKey.equals(apiKey)) {
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"principalId\":\"p\","
                                + "\"permissions\":[\"task:create\",\"task:edit\",\"task:view\",\"worker:poll\"],"
                                + "\"projectScopes\":[\"*\"],\"eventScopes\":[\"*\"]}}");
            } else if (staleFirstWorkerCredential && "node-worker-realtime-key".equals(apiKey)) {
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"credential\":{\"keyId\":\"ak-stale\"},"
                                + "\"principalId\":\"scenario-worker-node-worker-realtime-001\","
                                + "\"permissions\":[\"worker:poll\"],\"attributes\":{}}}");
            } else {
                writeJson(exchange, 401, "{\"code\":401,\"msg\":\"Invalid or missing API key credential\",\"data\":null}");
            }
        });
        server.createContext("/api/v1/api-keys/ak-stale:revoke", exchange -> {
            revokeCalls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Mass-Csrf-Token")).isEqualTo("csrf-1");
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("MASS_OPERATOR_SESSION=session-1");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(requestBody).contains("scenario worker credential rebind");
            writeJson(exchange, 200,
                    "{\"code\":200,\"msg\":\"success\",\"data\":{\"keyId\":\"ak-stale\",\"status\":\"REVOKED\"}}");
        });
        server.createContext("/api/v1/catalog/events/crawler.fetch-page", exchange ->
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"projectCodes\":[\"crawlerApp\"]}}"));
        server.createContext("/api/v1/catalog/events/stock.quote.fetch", exchange ->
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"projectCodes\":[\"crawlerApp\"]}}"));
        server.createContext("/api/v1/catalog/events/probe.phone.metadata", exchange ->
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"projectCodes\":[\"deviceProbe\"]}}"));
        server.createContext("/api/v1/auth/config", exchange ->
                writeJson(exchange, 200,
                        "{\"code\":200,\"msg\":\"success\",\"data\":{\"authMode\":\"session\","
                                + "\"operatorHeaderSupported\":false,\"sessionCookieSupported\":true,"
                                + "\"csrfHeaderName\":\"X-Mass-Csrf-Token\"}}"));
        server.createContext("/api/v1/auth/login", exchange -> {
            loginCalls.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "MASS_OPERATOR_SESSION=session-1; Path=/");
            writeJson(exchange, 200, "{\"code\":200,\"msg\":\"success\",\"data\":{\"csrfToken\":\"csrf-1\"}}");
        });
        server.createContext("/api/v1/control-plane/catalog:sync", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Mass-Csrf-Token")).isEqualTo("csrf-1");
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("MASS_OPERATOR_SESSION=session-1");
            writeJson(exchange, 200,
                    "{\"code\":200,\"msg\":\"success\",\"data\":{\"events\":2,\"projects\":1,\"rules\":0}}");
        });
        server.createContext("/api/v1/control-plane/rules:sync", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Mass-Csrf-Token")).isEqualTo("csrf-1");
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("MASS_OPERATOR_SESSION=session-1");
            writeJson(exchange, 200,
                    "{\"code\":200,\"msg\":\"success\",\"data\":{\"events\":0,\"projects\":0,\"rules\":3}}");
        });
        server.createContext("/api/v1/api-keys", exchange -> {
            createCalls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Mass-Csrf-Token")).isEqualTo("csrf-1");
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).contains("MASS_OPERATOR_SESSION=session-1");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (requestBody.contains("worker:poll")) {
                if (requestBody.contains("\"workerId\"")) {
                    assertThat(requestBody).contains("\"rawSecret\"");
                }
                writeJson(exchange, 200, "{\"code\":200,\"msg\":\"success\",\"data\":{\"rawSecret\":\"new-worker-key\"}}");
            } else {
                assertThat(requestBody).contains("task:create");
                if (rejectDefaultTaskPrincipal
                        && requestBody.contains("\"principalId\":\"crawler-task-producer-local\"")) {
                    writeJson(exchange, 400,
                            "{\"code\":400,\"msg\":\"principal already has an API key credential: "
                                    + "crawler-task-producer-local\",\"data\":null}");
                    return;
                }
                if (rejectDefaultTaskPrincipal) {
                    assertThat(requestBody).contains("\"principalId\":\"crawler-task-producer-local-");
                }
                writeJson(exchange, 200, "{\"code\":200,\"msg\":\"success\",\"data\":{\"rawSecret\":\"new-task-key\"}}");
            }
        });
        server.start();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
