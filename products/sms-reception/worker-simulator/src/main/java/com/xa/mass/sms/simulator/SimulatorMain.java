package com.xa.mass.sms.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One local process owns the SIM world; HTTP supplies external SMS stimuli only. */
public final class SimulatorMain implements AutoCloseable {
    private final ListeningRegistry registry = new ListeningRegistry();
    private final List<JavaWorkerManager> managers = new ArrayList<>();
    private final List<Map<String, Object>> inventory = new ArrayList<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService httpExecutor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.CallerRunsPolicy());
    private final HttpServer http;
    private final Traffic traffic = new Traffic();
    private volatile boolean started;

    private SimulatorMain() throws Exception {
        URI platform = URI.create(env("SMS_PLATFORM_URL", "http://127.0.0.1:18390"));
        int[] counts = Arrays.stream(env("SMS_COUNTS", "20,20,20").split(","))
                .mapToInt(Integer::parseInt).toArray();
        if (counts.length != 3 || Arrays.stream(counts).anyMatch(n -> n < 1)
                || Arrays.stream(counts).sum() > 10_000) throw new IllegalArgumentException("Invalid SMS_COUNTS");
        http = HttpServer.create(new InetSocketAddress("127.0.0.1",
                Integer.parseInt(env("SMS_HOST_PORT", "18394"))), 128);
        String[] countries = {"CN", "US", "GB"};
        String[] prefixes = {"+861700", "+120255", "+447700"};
        for (int countryIndex = 0; countryIndex < countries.length; countryIndex++) {
            String country = countries[countryIndex];
            var reference = new AtomicReference<JavaWorkerManager>();
            var builder = JavaWorkerManager.builder(platform, "sms-" + country.toLowerCase(Locale.ROOT),
                    WorkerTransportType.WEBSOCKET);
            for (int index = 0; index < counts[countryIndex]; index++) {
                String key = country + "-" + index;
                String phone = prefixes[countryIndex] + String.format(Locale.ROOT, "%06d", index);
                var sim = registry.addSim(phone, country, () -> reference.get().snapshot(key).workerId(),
                        () -> reference.get().snapshot(key).state().name());
                builder.replica(key, () -> Map.of("phone", phone, "country", country,
                                "operator", "Preview SIM", "simulated", "true"),
                        List.of(WorkerEventDefinition.extension("sms.listen.start",
                                        WorkerEventParameterResolvers.jsonMap(),
                                        (request, reporter) -> Jsons.toJson(registry.listen(sim, request, reporter))),
                                WorkerEventDefinition.extension("sms.listen.cancel",
                                        WorkerEventParameterResolvers.jsonMap(),
                                        request -> Jsons.toJson(registry.cancel(sim, request)))));
                inventory.add(Map.of("phone", phone, "country", country));
            }
            JavaWorkerManager manager = builder.build();
            reference.set(manager);
            managers.add(manager);
        }
        http.setExecutor(httpExecutor);
        http.createContext("/", this::handle);
    }

    private void start() {
        managers.forEach(JavaWorkerManager::start);
        clock.scheduleWithFixedDelay(registry::expire, 100, 100, TimeUnit.MILLISECONDS);
        clock.scheduleWithFixedDelay(traffic::tick, 10, 10, TimeUnit.MILLISECONDS);
        started = true;
        http.start();
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        if ("GET".equals(exchange.getRequestMethod()) && "/".equals(exchange.getRequestURI().getPath())) {
            try (var resource = SimulatorMain.class.getResourceAsStream("simulator.html")) {
                byte[] html = Objects.requireNonNull(resource, "Simulator page").readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, html.length);
                try (var output = exchange.getResponseBody()) { output.write(html); }
            }
            return;
        }
        int status = 200;
        Object response;
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            response = switch (method + " " + path) {
                case "GET /health" -> Map.of("started", started, "prepared", managers.stream()
                        .flatMap(m -> m.snapshots().values().stream()).filter(s -> s.workerId() != null).count(),
                        "numbers", inventory.size());
                case "GET /inventory" -> {
                    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                    yield registry.inventory(Integer.parseInt(query.getOrDefault("offset", "0")),
                            Integer.parseInt(query.getOrDefault("limit", "100")));
                }
                case "GET /metrics" -> Map.of("host", registry.metrics(), "traffic", traffic.snapshot());
                case "GET /records" -> {
                    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                    yield registry.records(Integer.parseInt(query.getOrDefault("offset", "0")),
                            Integer.parseInt(query.getOrDefault("limit", "100")));
                }
                case "POST /sms" -> {
                    Map<String, Object> body = body(exchange);
                    if (!(body.get("text") instanceof String text)) throw new IllegalArgumentException("Invalid SMS text");
                    yield registry.receive(ListeningRegistry.string(body, "phone"),
                            ListeningRegistry.string(body, "smsId"), text);
                }
                case "POST /traffic/start" -> traffic.start(body(exchange));
                case "POST /traffic/stop" -> traffic.stop();
                default -> { status = 404; yield Map.of("message", "Not found"); }
            };
        } catch (IllegalArgumentException error) {
            status = 400; response = Map.of("message", error.getMessage());
        } catch (IllegalStateException error) {
            status = 409; response = Map.of("message", error.getMessage());
        } catch (Exception error) {
            status = 500; response = Map.of("message", "Simulator operation failed");
        }
        byte[] encoded = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, encoded.length);
        try (var stream = exchange.getResponseBody()) { stream.write(encoded); }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws java.io.IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("Request too large");
        return Jsons.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> parsed = new HashMap<>();
        if (raw != null) for (String pair : raw.split("&")) {
            String[] entry = pair.split("=", 2);
            if (entry.length == 2) parsed.put(entry[0], entry[1]);
        }
        return parsed;
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }

    @Override public void close() {
        traffic.stop();
        clock.shutdownNow();
        registry.close();
        managers.forEach(JavaWorkerManager::close);
        http.stop(0);
        httpExecutor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        SimulatorMain host = new SimulatorMain();
        Runtime.getRuntime().addShutdownHook(new Thread(host::close, "sms-host-shutdown"));
        try { host.start(); }
        catch (Exception error) { host.close(); throw error; }
        new CountDownLatch(1).await();
    }

    private final class Traffic {
        private final Random random = new Random(20260909L);
        private long next;
        private long deadline;
        private long generated;
        private int rate;
        private long sequence;
        private String error = "";
        private List<String> phones = List.of();
        synchronized Map<String, Object> start(Map<String, Object> input) {
            if (rate != 0) throw new IllegalStateException("Traffic is already running");
            int selectedRate = Math.toIntExact(ListeningRegistry.number(input, "ratePerSecond"));
            long duration = ListeningRegistry.number(input, "durationSeconds");
            if (selectedRate < 1 || selectedRate > 1000 || duration < 1 || duration > 300
                    || selectedRate * duration > 100_000) throw new IllegalArgumentException("Invalid finite traffic budget");
            rate = selectedRate; next = System.nanoTime(); deadline = next + duration * 1_000_000_000L;
            generated = 0; error = ""; phones = registry.phones();
            return snapshot();
        }
        synchronized Map<String, Object> stop() { rate = 0; return snapshot(); }
        synchronized Map<String, Object> snapshot() {
            return Map.of("running", rate != 0, "ratePerSecond", rate, "generated", generated, "error", error);
        }
        void tick() {
            // A finite batch prevents a delayed timer from producing an unbounded catch-up burst.
            for (int i = 0; i < 32; i++) {
                String phone;
                String text;
                String smsId;
                synchronized (this) {
                    long now = System.nanoTime();
                    if (rate == 0 || now < next) return;
                    if (now >= deadline) { rate = 0; return; }
                    phone = phones.get(random.nextInt(phones.size()));
                    int type = random.nextInt(3);
                    text = type == 2 ? "Preview service announcement" : (type == 0 ? "[A] " : "[B] ")
                            + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                    smsId = "auto-" + sequence++;
                    next += 1_000_000_000L / rate;
                    generated++;
                }
                try { registry.receive(phone, smsId, text); }
                catch (RuntimeException failure) {
                    synchronized (this) { error = "SMS capacity or input rejected"; rate = 0; }
                    return;
                }
            }
        }
    }
}
