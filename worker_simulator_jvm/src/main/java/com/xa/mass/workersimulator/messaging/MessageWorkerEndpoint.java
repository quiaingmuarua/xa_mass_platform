package com.xa.mass.workersimulator.messaging;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.function.Function;
import static com.xa.mass.workersimulator.messaging.MessageProtocol.*;

/** Worker-owned sending admission and original-run callback associations. */
public final class MessageWorkerEndpoint implements AutoCloseable {
    private final Object gate = new Object();
    private final Map<String, Sender> senders = new LinkedHashMap<>();
    private final LinkedHashMap<String, Association> associations = new LinkedHashMap<>();
    private static final long SEND_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Semaphore sendAdmissions = new Semaphore(64 + 256);
    private final Semaphore sends = new Semaphore(64, true);
    private final MessageSendOperation operation;
    private final MessageSettings settings;
    private final LongSupplier monotonic;
    private boolean started, closed;
    private int pending, accepted;
    private long skippedAssociations, expiredAssociations, sendAttempts, sendCapacityRejected;

    MessageWorkerEndpoint(MessageSendOperation operation, MessageSettings settings, LongSupplier monotonic) {
        this.operation = Objects.requireNonNull(operation); this.settings = Objects.requireNonNull(settings);
        this.monotonic = Objects.requireNonNull(monotonic);
    }

    void start() {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages closed");
            if (started) throw new IllegalStateException("Messages already started");
            started = true;
        }
    }

    public Sender addSender(String group, String replica, Supplier<Map<String, String>> properties,
            Supplier<String> workerId, Supplier<String> runtimeState) {
        synchronized (gate) {
            var sender = new Sender(group, replica, properties, workerId, runtimeState);
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
        boolean acquired = false, callStarted = false;
        String callbackId = null;
        try {
            // Park the existing Handler, never start another thread or retry an external effect.
            // Queueing and the attempt share the original five-second call budget.
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
            synchronized (gate) {
                expireAssociations();
                if (closed || !started || !sender.accepting || !"RUNNING".equals(sender.runtimeState.get()))
                    throw new IllegalStateException("Sender is stopped or sending unavailable");
                if (pending >= 1024 || associations.size() >= settings.maxTrackedMessages()) skippedAssociations++;
                else {
                    callbackId = UUID.randomUUID().toString();
                    var association = new Association(sender, Map.copyOf(request), snapshot, Objects.requireNonNull(reporter), monotonic.getAsLong());
                    associations.put(callbackId, association); sender.callbacks.add(callbackId); pending++;
                }
            }
            long remaining = SEND_BUDGET_NANOS - (System.nanoTime() - began);
            if (remaining <= 0) {
                synchronized (gate) { sendCapacityRejected++; }
                throw new IllegalStateException("Message send capacity wait expired");
            }
            callStarted = true;
            var response = operation.send(request, snapshot, callbackId, Duration.ofNanos(remaining));
            String adopted = response.callbackId();
            Map<String, Object> sent = response.snapshot();
            if (!sameRequest(request, sent) || !"SENT".equals(sent.get("status")))
                throw new IllegalStateException("Invalid sending acceptance response");
            synchronized (gate) {
                if (!Objects.equals(callbackId, adopted)) remove(callbackId);
                Association original = associations.get(adopted);
                if (original != null && expired(original)) { remove(adopted); expiredAssociations++; original = null; }
                if (original != null && sameRequest(original.request, sent)
                        && original.senderSnapshot.get("workerId").equals(sent.get("workerId"))
                        && original.senderSnapshot.get("phone").equals(sent.get("phone"))) confirm(original);
            }
            return sent;
        } catch (MessageSendOperation.Rejected rejected) {
            synchronized (gate) { remove(callbackId); }
            if (rejected.invalidInput) throw new IllegalArgumentException(rejected.getMessage());
            throw new IllegalStateException(rejected.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(callStarted ? "Message send interrupted; acceptance unknown" : "Message send interrupted before sending", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Message send failed; acceptance unknown", failure);
        } finally {
            if (!callStarted) synchronized (gate) { remove(callbackId); }
            if (acquired) sends.release();
            sendAdmissions.release();
        }
    }

    /** Worker ingress. No receiving-service state is consulted to resolve an original Reporter. */
    public Map<String, Object> receive(String group, String replica, Map<String, Object> payload) {
        if (!payload.keySet().equals(Set.of("callbackId", "receiptId", "snapshot")))
            throw new IllegalArgumentException("Invalid receipt fields");
        String id = text(payload, "callbackId", 128); text(payload, "receiptId", 128);
        Map<String, Object> snapshot = object(payload, "snapshot");
        int tag = receiptTag(snapshot);
        long time = ((Number) snapshot.get("observedAtMillis")).longValue();
        WorkerOutcomeReporter reporter;
        synchronized (gate) {
            Association association = associations.get(id);
            if (association != null && expired(association)) { remove(id); expiredAssociations++; association = null; }
            if (closed || association == null || !association.sender.group.equals(group) || !association.sender.replica.equals(replica))
                throw new MissingCorrelation();
            if (!sameRequest(association.request, snapshot) || !association.senderSnapshot.get("workerId").equals(snapshot.get("workerId"))
                    || !association.senderSnapshot.get("phone").equals(snapshot.get("phone")))
                throw new IllegalArgumentException("Receipt association conflict");
            confirm(association); reporter = association.reporter;
        }
        boolean reported;
        try { reported = reporter.report(tag, time, Jsons.toJson(snapshot)); }
        catch (RuntimeException failure) { reported = false; }
        return Map.of("reportAccepted", reported);
    }

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
    void expireAssociations() {
        synchronized (gate) {
            for (int i = 0; i < 100 && !associations.isEmpty(); i++) {
                var first = associations.firstEntry();
                if (!expired(first.getValue())) break;
                remove(first.getKey()); expiredAssociations++;
            }
        }
    }
    boolean admitsCallbacks() { synchronized (gate) { return started && !closed; } }

    public Map<String, Object> metrics() {
        var result = new LinkedHashMap<String, Object>();
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
    /** Only the Host's bounded receiving admission may execute in this critical section. */
    <T> T admitReceiver(Map<String, Object> sender, String callbackId, Function<String, T> admit) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Messages closed");
            Sender registered = senders.get(text(sender, "workerGroupId", 256) + "/" + text(sender, "replicaKey", 256));
            if (registered == null) throw new IllegalArgumentException("Unknown sender coordinates");
            Association association = associations.get(callbackId);
            String usable = association != null && association.sender == registered && !expired(association) ? callbackId : null;
            return admit.apply(usable);
        }
    }

    /** Revocation and receiver cleanup share the same gate as receiving admission. */
    void stopWithCleanup(Sender sender, Runnable cleanup) {
        synchronized (gate) {
            sender.accepting = false; List.copyOf(sender.callbacks).forEach(this::remove);
            cleanup.run();
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
        synchronized (gate) {
            if (closed) return;
            closed = true; List.copyOf(associations.keySet()).forEach(this::remove);
        }
    }
    public static final class MissingCorrelation extends RuntimeException {}
    public static final class Sender {
        final String group, replica; final Supplier<Map<String, String>> properties; final Supplier<String> workerId, runtimeState;
        public String group() { return group; }
        public String replica() { return replica; }
        final Set<String> callbacks = new HashSet<>(); boolean accepting = true;
        Sender(String group, String replica, Supplier<Map<String, String>> properties, Supplier<String> workerId, Supplier<String> runtimeState) {
            this.group = group; this.replica = replica; this.properties = properties; this.workerId = workerId; this.runtimeState = runtimeState;
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
