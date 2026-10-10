package com.xa.mass.workersimulator.messaging;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Fixed Messages composition: Lab facts, business HTTP, and run-owned Worker correlations. */
public final class MessageScenario implements AutoCloseable {
    public static final String SEND_EVENT = "extension.worker.message.send";
    public static final int MAX_HELD = 10_000, MAX_REPLY_IDS = 100_000;
    private final Object gate = new Object();
    private final Map<String, Sender> senders = new LinkedHashMap<>();
    private final LinkedHashMap<String, Association> associations = new LinkedHashMap<>();
    private static final long SEND_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Semaphore sendAdmissions = new Semaphore(64 + 256);
    private final Semaphore sends = new Semaphore(64, true);
    private final MessageLab lab;
    private final MessageSettings settings;
    private final LongSupplier monotonic;
    private URI baseUri;
    private HttpClient http;
    private ThreadPoolExecutor callbacks;
    private boolean closed;
    private int pending, accepted;
    private long skippedAssociations, expiredAssociations;
    private long sendAttempts, sendCapacityRejected;

    public MessageScenario() { this(0); }
    public MessageScenario(long seed) { this(seed, MessageSettings.defaults()); }
    public MessageScenario(long seed, MessageSettings settings) { this(seed, settings, System::currentTimeMillis, System::nanoTime); }
    MessageScenario(long seed, MessageSettings settings, LongSupplier clock, LongSupplier monotonic) {
        this.settings = Objects.requireNonNull(settings); this.monotonic = Objects.requireNonNull(monotonic);
        lab = new MessageLab(seed, settings, clock, monotonic, this::queueCallback, this::expireAssociations);
    }

    /** Called once the Host listener is bound and routes/Workers have been assembled. */
    public void startHttp(URI listener) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages closed");
            if (http != null) throw new IllegalStateException("Messages HTTP already started");
            if (!"http".equals(listener.getScheme()) || !"127.0.0.1".equals(listener.getHost())
                    || listener.getPort() < 1 || listener.getRawQuery() != null || listener.getRawUserInfo() != null)
                throw new IllegalArgumentException("Expected the Host loopback listener");
            baseUri = listener;
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            callbacks = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "message-lab-callback"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
        lab.start();
    }

    public Sender addSender(String group, String replica, Supplier<Map<String, String>> properties,
            Supplier<String> workerId, Supplier<String> runtimeState, MessageContentMode mode) {
        synchronized (gate) {
            var sender = new Sender(group, replica, properties, workerId, runtimeState, mode);
            if (senders.putIfAbsent(group + "/" + replica, sender) != null) throw new IllegalArgumentException("Duplicate sender");
            return sender;
        }
    }
    public List<WorkerEventDefinition<?>> definitions(Sender sender) {
        return List.of(WorkerEventDefinition.extension("message.send", WorkerEventParameterResolvers.jsonMap(),
                (request, reporter) -> Jsons.toJson(send(sender, request, reporter))));
    }

    public Map<String, Object> send(Sender sender, Map<String, Object> request, WorkerOutcomeReporter reporter) {
        validateMessage(request);
        synchronized (gate) { sendAttempts++; }
        if (!sendAdmissions.tryAcquire()) {
            synchronized (gate) { sendCapacityRejected++; }
            throw new IllegalStateException("Message send capacity exhausted");
        }
        long began = System.nanoTime();
        boolean acquired = false, httpStarted = false;
        String callbackId = null;
        try {
            // Park the existing Handler, never start another thread or retry an external effect.
            // Queueing and HTTP share the original five-second call budget.
            if (!sends.tryAcquire(SEND_BUDGET_NANOS, TimeUnit.NANOSECONDS)) {
                synchronized (gate) { sendCapacityRejected++; }
                throw new IllegalStateException("Message send capacity wait expired");
            }
            acquired = true;
            Map<String, String> properties = sender.properties.get();
            String worker = sender.workerId.get();
            if (worker == null || worker.isBlank()) throw new IllegalStateException("Sender identity unavailable");
            Map<String, Object> snapshot = Map.of("workerGroupId", sender.group, "replicaKey", sender.replica,
                    "workerId", worker, "phone", properties.get("phone"), "country", properties.get("country"));
            HttpClient client;
            URI target;
            synchronized (gate) {
                expireAssociations();
                if (closed || http == null || !sender.accepting || !"RUNNING".equals(sender.runtimeState.get()))
                    throw new IllegalStateException("Sender is stopped or business HTTP unavailable");
                if (pending >= 1024 || associations.size() >= settings.maxTrackedMessages()) skippedAssociations++;
                else {
                    callbackId = UUID.randomUUID().toString();
                    var association = new Association(sender, Map.copyOf(request), snapshot, Objects.requireNonNull(reporter), monotonic.getAsLong());
                    associations.put(callbackId, association); sender.callbacks.add(callbackId); pending++;
                }
                client = http; target = baseUri.resolve("/lab/v1/messages/send");
            }
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("message", request); envelope.put("sender", snapshot); envelope.put("callbackId", callbackId);
            long remaining = SEND_BUDGET_NANOS - (System.nanoTime() - began);
            if (remaining <= 0) {
                synchronized (gate) { sendCapacityRejected++; }
                throw new IllegalStateException("Message send capacity wait expired");
            }
            httpStarted = true;
            var response = post(client, target, envelope, Duration.ofNanos(remaining));
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                synchronized (gate) { remove(callbackId); }
                if (response.statusCode() == 400) throw new IllegalArgumentException("Lab rejected message input or identity conflict");
                throw new IllegalStateException("Lab rejected message acceptance");
            }
            if (response.statusCode() != 200) throw new IllegalStateException("Lab message response is uncertain");
            Map<String, Object> decoded = Jsons.parseObject(response.body());
            String adopted = nullableCallback(decoded);
            Map<String, Object> sent = object(decoded, "snapshot");
            if (!sameRequest(request, sent) || !"SENT".equals(sent.get("status")))
                throw new IllegalStateException("Invalid Lab acceptance response");
            synchronized (gate) {
                if (!Objects.equals(callbackId, adopted)) remove(callbackId);
                Association original = associations.get(adopted);
                if (original != null && expired(original)) { remove(adopted); expiredAssociations++; original = null; }
                if (original != null && sameRequest(original.request, sent)
                        && original.senderSnapshot.get("workerId").equals(sent.get("workerId"))
                        && original.senderSnapshot.get("phone").equals(sent.get("phone"))) confirm(original);
            }
            return sent;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(httpStarted ? "Message send interrupted; acceptance unknown" : "Message send interrupted before HTTP", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Message send failed; acceptance unknown", failure);
        } finally {
            if (!httpStarted) synchronized (gate) { remove(callbackId); }
            if (acquired) sends.release();
            sendAdmissions.release();
        }
    }

    /** Lab ingress; can execute before the send response and before Host READY. */
    public Map<String, Object> accept(Map<String, Object> input) {
        if (!input.keySet().equals(Set.of("message", "sender", "callbackId"))) throw new IllegalArgumentException("Invalid send envelope");
        Map<String, Object> sender = object(input, "sender");
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages closed");
            Sender registered = senders.get(text(sender, "workerGroupId", 256) + "/" + text(sender, "replicaKey", 256));
            if (registered == null)
                throw new IllegalArgumentException("Unknown sender coordinates");
            String callbackId = nullableCallback(input);
            Association association = associations.get(callbackId);
            if (association == null || association.sender != registered || expired(association)) callbackId = null;
            // Coordinate stop and new acceptance without retaining a stopped run's observation.
            // Lab queues callbacks; HTTP and Reporter execution remain outside both state gates.
            return lab.accept(object(input, "message"), sender, callbackId, registered.mode);
        }
    }

    /** Worker ingress. No Lab state is consulted to resolve an original Reporter. */
    public Map<String, Object> receive(String group, String replica, Map<String, Object> payload) {
        if (!payload.keySet().equals(Set.of("callbackId", "receiptId", "snapshot")))
            throw new IllegalArgumentException("Invalid receipt fields");
        String id = text(payload, "callbackId", 128); text(payload, "receiptId", 128);
        Map<String, Object> snapshot = object(payload, "snapshot");
        String status = text(snapshot, "status", 16);
        int tag = switch (status) { case "DELIVERED" -> 7; case "READ" -> 8; case "REPLIED" -> 9;
            default -> throw new IllegalArgumentException("Invalid receipt status"); };
        Set<String> required = new HashSet<>(Set.of("campaignId", "messageId", "country", "recipientId", "body",
                "status", "workerId", "phone", "observedAtMillis"));
        if (tag == 9) { required.add("reply"); required.add("replyRequestId"); text(snapshot, "reply", 4096); text(snapshot, "replyRequestId", 128); }
        if (!snapshot.keySet().equals(required) || !(snapshot.get("observedAtMillis") instanceof Number time)
                || !Double.isFinite(time.doubleValue()) || time.longValue() <= 0 || time.doubleValue() != time.longValue())
            throw new IllegalArgumentException("Invalid receipt snapshot");
        WorkerOutcomeReporter reporter;
        synchronized (gate) {
            Association association = associations.get(id);
            if (association != null && expired(association)) { remove(id); expiredAssociations++; association = null; }
            if (closed || association == null || !association.sender.group.equals(group) || !association.sender.replica.equals(replica))
                throw new MissingMessage();
            if (!sameRequest(association.request, snapshot) || !association.senderSnapshot.get("workerId").equals(snapshot.get("workerId"))
                    || !association.senderSnapshot.get("phone").equals(snapshot.get("phone")))
                throw new IllegalArgumentException("Receipt association conflict");
            confirm(association); reporter = association.reporter;
        }
        boolean reported;
        try { reported = reporter.report(tag, time.longValue(), Jsons.toJson(snapshot)); }
        catch (RuntimeException failure) { reported = false; }
        return Map.of("reportAccepted", reported);
    }

    private boolean queueCallback(MessageLab.Receipt receipt) {
        HttpClient client;
        URI target;
        ThreadPoolExecutor executor;
        synchronized (gate) {
            if (closed || http == null) return false;
            client = http; executor = callbacks;
            target = baseUri.resolve("/lab/v1/workers/" + segment(receipt.sender().get("workerGroupId")) + "/"
                    + segment(receipt.sender().get("replicaKey")) + ":inputs");
        }
        try {
            executor.execute(() -> {
                int status = 0; Boolean reported = null; String failure = null;
                try {
                    var response = post(client, target, Map.of("eventName", "message.receipt", "payload",
                            Map.of("callbackId", receipt.callbackId(), "receiptId", receipt.id(), "snapshot", receipt.snapshot())));
                    status = response.statusCode();
                    if (status == 200) {
                        Object result = Jsons.parseObject(response.body()).get("reportAccepted");
                        if (!(result instanceof Boolean)) throw new IllegalArgumentException("Invalid callback response");
                        reported = (Boolean) result;
                    }
                    else failure = "callback_http_rejected";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); failure = "callback_interrupted";
                } catch (Exception error) { failure = "callback_transport_failed"; }
                lab.callbackCompleted(receipt, status, reported, failure);
            });
            return true;
        } catch (RejectedExecutionException full) { return false; }
    }

    private static HttpResponse<String> post(HttpClient client, URI target, Map<String, Object> payload)
            throws java.io.IOException, InterruptedException {
        return post(client, target, payload, Duration.ofSeconds(5));
    }
    private static HttpResponse<String> post(HttpClient client, URI target, Map<String, Object> payload, Duration timeout)
            throws java.io.IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(target).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String segment(Object value) { return URLEncoder.encode((String) value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private void confirm(Association association) {
        if (!association.removed && !association.confirmed) { association.confirmed = true; pending--; accepted++; }
    }
    private void remove(String id) {
        Association association = associations.remove(id);
        if (association == null) return;
        association.removed = true; association.sender.callbacks.remove(id);
        if (association.confirmed) accepted--; else pending--;
    }
    private boolean expired(Association association) {
        return monotonic.getAsLong() - association.createdNanos >= settings.receiptWindowNanos();
    }
    private void expireAssociations() {
        synchronized (gate) {
            for (int i = 0; i < 100 && !associations.isEmpty(); i++) {
                var first = associations.firstEntry();
                if (!expired(first.getValue())) break;
                remove(first.getKey()); expiredAssociations++;
            }
        }
    }
    private static String nullableCallback(Map<String, Object> value) {
        if (!value.containsKey("callbackId")) throw new IllegalArgumentException("Missing callbackId");
        return value.get("callbackId") == null ? null : text(value, "callbackId", 128);
    }
    public Map<String, Object> acceptances(List<String> ids) { return lab.acceptances(ids); }
    public Map<String, Object> act(Sender sender, String id, String action, Map<String, Object> input) {
        return lab.act(sender.group, sender.replica, id, action, input);
    }
    public Map<String, Object> hold(boolean enabled) { return lab.hold(enabled); }
    public Map<String, Object> release(List<String> ids) { return lab.release(ids); }
    public Map<String, Object> page(int offset, int limit) { return lab.page(null, null, offset, limit); }
    public Map<String, Object> page(Sender sender, int offset, int limit) { return lab.page(sender.group, sender.replica, offset, limit); }
    public Map<String, Object> metrics() {
        var result = new LinkedHashMap<>(lab.metrics());
        synchronized (gate) {
            result.put("reporters", accepted); result.put("pendingAssociations", pending);
            result.put("skippedAssociations", skippedAssociations); result.put("expiredAssociations", expiredAssociations);
            result.put("sendAttempts", sendAttempts); result.put("sendCapacityRejected", sendCapacityRejected);
            result.put("sendInFlight", 64 - sends.availablePermits()); result.put("queuedSends", sends.getQueueLength());
        }
        return result;
    }
    public Map<String, Object> inventory(int offset, int limit) {
        pageBounds(offset, limit);
        synchronized (gate) {
            return Map.of("total", senders.size(), "items", senders.values().stream().skip(offset).limit(limit).map(sender -> {
                var item = new LinkedHashMap<String, Object>();
                Map<String, String> properties = sender.properties.get();
                item.put("workerGroupId", sender.group); item.put("replicaKey", sender.replica); item.put("country", properties.get("country"));
                item.put("phone", properties.get("phone")); item.put("workerId", sender.workerId.get()); item.put("runtimeState", sender.runtimeState.get());
                return item;
            }).toList());
        }
    }
    public void stop(Sender sender) {
        synchronized (gate) {
            sender.accepting = false; List.copyOf(sender.callbacks).forEach(this::remove);
            lab.stop(sender.group, sender.replica);
        }
    }
    public void start(Sender sender) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message channel closed");
            sender.accepting = true;
        }
    }
    public boolean startStillRequested(Sender sender) { synchronized (gate) { return !closed && sender.accepting; } }
    @Override public void close() {
        HttpClient closingHttp; ThreadPoolExecutor closingCallbacks;
        synchronized (gate) {
            if (closed) return;
            closed = true; List.copyOf(associations.keySet()).forEach(this::remove);
            closingHttp = http; closingCallbacks = callbacks;
        }
        lab.close();
        if (closingCallbacks != null) closingCallbacks.shutdownNow();
        if (closingHttp != null) closingHttp.shutdownNow();
        // No synchronous flush, replay, or wait for a Worker callback on shutdown.
    }
    static void validateMessage(Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("campaignId", "messageId", "country", "recipientId", "body")))
            throw new IllegalArgumentException("Invalid send fields");
        text(request, "campaignId", 128); text(request, "messageId", 128); text(request, "recipientId", 128);
        text(request, "body", 4096); text(request, "country", 2);
    }
    private static boolean sameRequest(Map<String, Object> request, Map<String, Object> snapshot) {
        return request.entrySet().stream().allMatch(entry -> Objects.equals(entry.getValue(), snapshot.get(entry.getKey())));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw new IllegalArgumentException("Invalid " + key);
        return (Map<String, Object>) map;
    }
    public static String text(Map<String, Object> input, String key, int maximum) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    static void pageBounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
    }
    public static final class MissingMessage extends RuntimeException {}
    public static final class CapacityExceeded extends IllegalStateException {
        public CapacityExceeded(String message) { super(message); }
    }
    public static final class Sender {
        final String group, replica; final Supplier<Map<String, String>> properties; final Supplier<String> workerId, runtimeState;
        final MessageContentMode mode;
        final Set<String> callbacks = new HashSet<>(); boolean accepting = true;
        Sender(String group, String replica, Supplier<Map<String, String>> properties, Supplier<String> workerId, Supplier<String> runtimeState, MessageContentMode mode) {
            this.group = group; this.replica = replica; this.properties = properties; this.workerId = workerId; this.runtimeState = runtimeState;
            this.mode = Objects.requireNonNull(mode);
        }
    }
    private static final class Association {
        final Sender sender; final Map<String, Object> request, senderSnapshot; final WorkerOutcomeReporter reporter;
        final long createdNanos;
        boolean confirmed, removed;
        Association(Sender sender, Map<String, Object> request, Map<String, Object> senderSnapshot, WorkerOutcomeReporter reporter, long createdNanos) {
            this.sender = sender; this.request = request; this.senderSnapshot = senderSnapshot; this.reporter = reporter;
            this.createdNanos = createdNanos;
        }
    }
}
