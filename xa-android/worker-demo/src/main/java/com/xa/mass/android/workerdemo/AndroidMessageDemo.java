package com.xa.mass.android.workerdemo;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Process-local demo channel. Acceptance and optional original-run reporting share one owner. */
final class AndroidMessageDemo {
    static final String SEND_EVENT = "extension.worker.message.send";
    static final String READ_EVENT = "extension.worker.android-demo.host.messages.read";
    static final int MAX_ACCEPTED = 1_000, MAX_REPORTERS = 256, MAX_RECENT = 100;
    static final long WINDOW_MILLIS = 600_000;
    private static final List<String> FIELDS = Collections.unmodifiableList(
            Arrays.asList("campaignId", "messageId", "country", "recipientId", "body"));
    private final Object gate = new Object();
    private final LinkedHashMap<String, Accepted> accepted = new LinkedHashMap<>();
    private final Map<String, Accepted> tokens = new HashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final Supplier<WorkerLifecycle.Snapshot> worker;
    private final String phone;
    private final LongSupplier elapsedMillis, wallMillis;
    private final int maximumAccepted, maximumReporters, maximumRecent;
    private final long windowMillis;
    private int reporters;

    AndroidMessageDemo(Supplier<WorkerLifecycle.Snapshot> worker, String phone) {
        this(worker, phone, android.os.SystemClock::elapsedRealtime, System::currentTimeMillis,
                MAX_ACCEPTED, MAX_REPORTERS, MAX_RECENT, WINDOW_MILLIS);
    }

    AndroidMessageDemo(Supplier<WorkerLifecycle.Snapshot> worker, String phone,
                       LongSupplier elapsedMillis, LongSupplier wallMillis,
                       int maximumAccepted, int maximumReporters, int maximumRecent, long windowMillis) {
        this.worker = Objects.requireNonNull(worker);
        this.phone = Objects.requireNonNull(phone);
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis);
        this.wallMillis = Objects.requireNonNull(wallMillis);
        if (phone.trim().isEmpty() || maximumAccepted < 1 || maximumReporters < 1
                || maximumRecent < 1 || windowMillis < 1) throw new IllegalArgumentException("Invalid demo bounds");
        this.maximumAccepted = maximumAccepted;
        this.maximumReporters = maximumReporters;
        this.maximumRecent = maximumRecent;
        this.windowMillis = windowMillis;
    }

    WorkerEventDefinition<?> sendingDefinition() {
        return WorkerEventDefinition.extension("message.send", WorkerEventParameterResolvers.jsonMap(),
                (parameters, reporter) -> Jsons.toJson(send(parameters, reporter)));
    }

    WorkerEventDefinition<?> observationDefinition() {
        return WorkerEventDefinition.extension("android-demo.host.messages.read",
                WorkerEventParameterResolvers.jsonMap(), parameters -> Jsons.toJson(inspect(parameters)));
    }

    Map<String, Object> send(Map<String, Object> input, WorkerOutcomeReporter reporter) {
        Map<String, Object> message = validate(input);
        Objects.requireNonNull(reporter);
        WorkerLifecycle.Snapshot sender = worker.get();
        if (sender == null || sender.state() != WorkerLifecycle.State.RUNNING
                || sender.workerId() == null || sender.workerId().trim().isEmpty())
            throw new IllegalStateException("Message sender unavailable");
        Map<String, Object> sent;
        synchronized (gate) {
            long now = elapsedMillis.getAsLong();
            expire(now);
            String id = (String) message.get("messageId");
            Accepted previous = find(id, now);
            if (previous != null) {
                if (!previous.input.equals(message)) throw new IllegalArgumentException("Message identity conflict");
                return previous.sent;
            }
            if (accepted.size() >= maximumAccepted) throw new IllegalStateException("Message acceptance capacity exhausted");
            long observed = wallMillis.getAsLong();
            if (observed <= 0) throw new IllegalStateException("Message observation clock unavailable");
            Map<String, Object> snapshot = new LinkedHashMap<>(message);
            snapshot.put("status", "SENT");
            snapshot.put("workerId", sender.workerId());
            snapshot.put("phone", phone);
            snapshot.put("observedAtMillis", observed);
            sent = Collections.unmodifiableMap(snapshot);
            WorkerOutcomeReporter original = reporters < maximumReporters && reporter != WorkerOutcomeReporter.UNAVAILABLE
                    ? reporter : null;
            Accepted record = new Accepted(message, sent, now, original);
            accepted.put(id, record);
            tokens.put(record.token, record);
            if (original != null) reporters++;
            recent.addLast(id);
            while (recent.size() > maximumRecent) recent.removeFirst();
        }
        publish();
        return sent;
    }

    private static Map<String, Object> validate(Map<String, Object> input) {
        if (input == null || !input.keySet().equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("Expected the five message fields");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String field : FIELDS) {
            int maximum = field.equals("body") ? 4096 : field.equals("country") ? 2 : 128;
            copy.put(field, text(input.get(field), maximum));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String) || ((String) value).codePoints().allMatch(Character::isWhitespace)
                || ((String) value).length() > maximum) throw new IllegalArgumentException("Invalid message text");
        return (String) value;
    }

    List<Message> recentMessages() {
        synchronized (gate) {
            long now = elapsedMillis.getAsLong();
            expire(now);
            List<Message> messages = new ArrayList<>();
            Iterator<String> ids = recent.descendingIterator();
            while (ids.hasNext()) {
                Accepted record = accepted.get(ids.next());
                if (record != null && !expired(record, now)) messages.add(view(record));
            }
            return Collections.unmodifiableList(messages);
        }
    }

    Message message(String token) {
        synchronized (gate) {
            Accepted record = tokens.get(token);
            if (record == null) return null;
            if (expired(record, elapsedMillis.getAsLong())) {
                remove(record);
                return null;
            }
            return view(record);
        }
    }

    /** READ is attempted once per local opening, never by a list refresh or notification publication. */
    String opened(String token) { return report(token, null, null); }
    String reply(String token, String reply, String requestId) {
        return report(token, text(reply, 4096), text(requestId, 128));
    }

    private String report(String token, String reply, String requestId) {
        Accepted record;
        WorkerOutcomeReporter reporter;
        Map<String, Object> snapshot;
        String status = reply == null ? "READ" : "REPLIED";
        synchronized (gate) {
            record = tokens.get(token);
            if (record == null || expired(record, elapsedMillis.getAsLong()))
                return "关联已过期，无法上报";
            if (record.reporter == null) return record.feedback;
            if (record.reporting) return "正在提交回执";
            if (reply == null && record.opened) return record.feedback;
            if (reply == null) record.opened = true;
            record.interacted = true;
            record.reporting = true;
            reporter = record.reporter;
            snapshot = new LinkedHashMap<>(record.sent);
            snapshot.put("status", status);
            snapshot.put("observedAtMillis", wallMillis.getAsLong());
            if (reply != null) {
                snapshot.put("reply", reply);
                snapshot.put("replyRequestId", requestId);
            }
        }
        boolean submitted;
        try {
            submitted = reporter.report(reply == null ? 8 : 9,
                    ((Number) snapshot.get("observedAtMillis")).longValue(), Jsons.toJson(snapshot));
        } catch (RuntimeException failure) {
            submitted = false;
        }
        String feedback;
        synchronized (gate) {
            record.reporting = false;
            feedback = submitted ? status + " 回执已提交，平台结果请刷新查看" : "回执未被接受；未自动重试";
            // Stop may have revoked the reference while this admitted callback completed.
            if (record.reporter == reporter) record.feedback = feedback;
        }
        publish();
        return feedback;
    }

    void revokeReporters() {
        synchronized (gate) {
            for (Accepted record : accepted.values()) {
                record.reporter = null;
                record.feedback = "原 Worker 已停止，无法上报";
            }
            reporters = 0;
        }
        publish();
    }

    Map<String, Object> inspect(Map<String, Object> request) {
        if (!request.keySet().equals(Collections.singleton("messageIds")) || !(request.get("messageIds") instanceof List<?>))
            throw new IllegalArgumentException("Expected messageIds");
        List<?> ids = (List<?>) request.get("messageIds");
        if (ids.isEmpty() || ids.size() > 100) throw new IllegalArgumentException("Expected 1..100 message IDs");
        Set<String> unique = new LinkedHashSet<>();
        for (Object id : ids) unique.add(text(id, 128));
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        synchronized (gate) {
            long now = elapsedMillis.getAsLong();
            expire(now);
            for (String id : unique) {
                Accepted record = find(id, now);
                if (record == null) { missing.add(id); continue; }
                Map<String, Object> evidence = new LinkedHashMap<>();
                for (String field : Arrays.asList("campaignId", "messageId", "workerId", "phone", "observedAtMillis"))
                    evidence.put(field, record.sent.get(field));
                evidence.put("requestFingerprint", record.fingerprint);
                evidence.put("receiptAvailable", record.reporter != null);
                found.add(evidence);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", found);
        result.put("missing", missing);
        return result;
    }

    private Accepted find(String id, long now) {
        Accepted record = accepted.get(id);
        if (record != null && expired(record, now)) { remove(record); return null; }
        return record;
    }
    private boolean expired(Accepted record, long now) { return now - record.createdAt >= windowMillis; }
    private void expire(long now) {
        for (int count = 0; count < 100 && !accepted.isEmpty(); count++) {
            Accepted first = accepted.values().iterator().next();
            if (!expired(first, now)) break;
            remove(first);
        }
    }
    private void remove(Accepted record) {
        String id = (String) record.input.get("messageId");
        accepted.remove(id);
        tokens.remove(record.token);
        recent.remove(id);
        if (record.reporter != null) reporters--;
        record.reporter = null;
    }
    void addListener(Runnable listener) { listeners.add(Objects.requireNonNull(listener)); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    private void publish() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Observation cannot undo acceptance. */ }
        }
    }
    private Message view(Accepted record) {
        return new Message(record.token, (String) record.input.get("messageId"),
                (String) record.input.get("recipientId"), (String) record.input.get("body"),
                record.reporter != null, record.interacted, record.feedback);
    }
    private static String fingerprint(Map<String, Object> message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : FIELDS) {
                byte[] bytes = ((String) message.get(field)).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    static final class Message {
        final String token, messageId, recipient, body, feedback;
        final boolean receiptAvailable, interacted;
        Message(String token, String messageId, String recipient, String body, boolean receiptAvailable, boolean interacted, String feedback) {
            this.token = token; this.messageId = messageId; this.recipient = recipient;
            this.body = body; this.receiptAvailable = receiptAvailable; this.interacted = interacted; this.feedback = feedback;
        }
    }
    private static final class Accepted {
        final Map<String, Object> input, sent;
        final long createdAt;
        final String token = UUID.randomUUID().toString(), fingerprint;
        WorkerOutcomeReporter reporter;
        boolean opened, reporting, interacted;
        String feedback;
        Accepted(Map<String, Object> input, Map<String, Object> sent, long createdAt, WorkerOutcomeReporter reporter) {
            this.input = input; this.sent = sent; this.createdAt = createdAt; this.reporter = reporter;
            fingerprint = fingerprint(input);
            feedback = reporter == null ? "回执关联未保留，发送已受理" : "本地模拟已受理，尚未上报回执";
        }
    }
}
