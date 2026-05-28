package com.xa.mass.samples.polling;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.session.DispatchContext;
import com.xa.mass.client.worker.session.PollingWorkerSession;
import com.xa.mass.client.worker.session.WorkerResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PollingWorkerMain {

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]+)</title>",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final MassPlatform mass;
    private final String workerId;
    private final String workerGroupId;
    private final String adapterNodeId;
    private final String project;
    private final String eventCode;
    private final String region;
    private final String runtime;
    private final String[] routingTags;
    private final long pollIntervalMs;
    private final long heartbeatIntervalMs;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private volatile PollingWorkerSession session;

    private PollingWorkerMain() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String baseUrl = env("MASS_BASE_URL", "http://127.0.0.1:8088");
        this.workerId = requiredEnv("MASS_WORKER_ID", "java-worker-api-001");
        String workerKey = requiredEnv("MASS_WORKER_KEY", "java-worker-key");
        this.workerGroupId = env("MASS_WORKER_GROUP_ID", "java-runtime");
        this.adapterNodeId = env("MASS_ADAPTER_NODE_ID", this.workerGroupId + "-node");
        this.project = env("MASS_PROJECT", "crawlerApp");
        this.eventCode = env("MASS_EVENT_CODE", "crawler.fetch-page");
        this.region = env("MASS_REGION", "us");
        this.runtime = "java-" + System.getProperty("java.version");
        this.routingTags = splitCsv(env("MASS_ROUTING_TAGS", "web," + region));
        this.pollIntervalMs = longEnv("MASS_POLL_INTERVAL_MS", 1000L);
        this.heartbeatIntervalMs = longEnv("MASS_HEARTBEAT_INTERVAL_MS", 10000L);
        this.mass = MassPlatform.builder()
                .baseUrl(baseUrl)
                .apiKey(workerKey)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static void main(String[] args) throws Exception {
        new PollingWorkerMain().run();
    }

    private void run() throws InterruptedException {
        log("starting polling worker " + workerId + " for " + eventCode + " at " + mass.baseUri());
        Runtime.getRuntime().addShutdownHook(new Thread(this::safeShutdown,
                "java-polling-worker-shutdown"));

        declareWorkerGroup();
        session = mass.workerSessions().polling()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .adapterNodeId(adapterNodeId)
                .adapterType("polling")
                .endpointId(adapterNodeId)
                .attribute("lang", "java")
                .attribute("runtime", runtime)
                .attribute("region", region)
                .attribute("country", region)
                .attribute("routingTags", String.join(",", routingTags))
                .pollInterval(Duration.ofMillis(pollIntervalMs))
                .heartbeatInterval(Duration.ofMillis(heartbeatIntervalMs))
                .maxMessages(10)
                .event(eventCode, this::handleDispatch)
                .start();

        shutdownLatch.await();
    }

    private void declareWorkerGroup() {
        mass.workers().declareGroup(WorkerGroupSpec.builder()
                .groupId(workerGroupId)
                .bindEvent(eventCode, List.of(project))
                .defaultAttribute("lang", "java")
                .defaultAttribute("region", region)
                .defaultAttribute("routingTags", String.join(",", routingTags))
                .build());
        log("declared worker group " + workerGroupId + " for " + eventCode);
    }

    private WorkerResult handleDispatch(DispatchContext dispatch) {
        log("received taskId=" + dispatch.taskId()
                + " messageId=" + dispatch.messageId()
                + " eventCode=" + dispatch.eventCode());
        if (!eventCode.equals(dispatch.eventCode())) {
            return WorkerResult.failure("UNSUPPORTED_EVENT",
                    "Unsupported eventCode: " + dispatch.eventCode(),
                    baseOutput(dispatch));
        }
        return handleCrawlerFetchPage(dispatch);
    }

    private WorkerResult handleCrawlerFetchPage(DispatchContext dispatch) {
        String url = lookupDispatchUrl(dispatch);
        if (url == null || url.isBlank()) {
            return WorkerResult.failure("INVALID_INPUT",
                    "url is required in TaskDispatchItem.input.url",
                    baseOutput(dispatch));
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Map<String, Object> output = fetchOutput(dispatch, url, startedAt);
            return WorkerResult.failure("FETCH_ERROR",
                    error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
                    output);
        } catch (IllegalArgumentException error) {
            return WorkerResult.failure("INVALID_INPUT", error.getMessage(), baseOutput(dispatch));
        }

        Map<String, Object> output = fetchOutput(dispatch, response.uri().toString(), startedAt);
        output.put("statusCode", response.statusCode());
        putIfNotNull(output, "title", extractHtmlTitle(response.body()));

        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        if (success) {
            return WorkerResult.success("crawler-success", output);
        }
        return WorkerResult.failure("HTTP_" + response.statusCode(),
                "crawler-http-" + response.statusCode(),
                output);
    }

    private String lookupDispatchUrl(DispatchContext dispatch) {
        return dispatch.input().getString("url")
                .or(() -> dispatch.sharedConfig().getString("url"))
                .orElse(null);
    }

    private Map<String, Object> fetchOutput(DispatchContext dispatch, String url, long startedAt) {
        Map<String, Object> output = baseOutput(dispatch);
        output.put("url", url);
        output.put("fetchedAt", Instant.now().toString());
        output.put("elapsedMs", System.currentTimeMillis() - startedAt);
        return output;
    }

    private Map<String, Object> baseOutput(DispatchContext dispatch) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerId", workerId);
        output.put("eventCode", dispatch.eventCode());
        output.put("integrationProbe", "cross-language-java-polling");
        output.put("workerProfile", workerProfile());
        return output;
    }

    private Map<String, Object> workerProfile() {
        Map<String, Object> workerProfile = new LinkedHashMap<>();
        workerProfile.put("runtime", "java-polling-worker");
        workerProfile.put("language", "java");
        workerProfile.put("workerId", workerId);
        return workerProfile;
    }

    private void safeShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        log("shutting down");
        PollingWorkerSession current = session;
        if (current != null) {
            current.close();
        }
        shutdownLatch.countDown();
    }

    private static void putIfNotNull(Map<String, Object> output, String key, Object value) {
        if (value != null) {
            output.put(key, value);
        }
    }

    private static String extractHtmlTitle(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = TITLE_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnv(String name, String fallback) {
        String value = env(name, fallback);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return parsed;
    }

    private static String[] splitCsv(String value) {
        return value == null || value.isBlank()
                ? new String[0]
                : value.trim().split("\\s*,\\s*");
    }

    private void log(String message) {
        System.out.println("[java-worker:" + workerId + "] " + message);
    }
}
