package com.xa.mass.workersimulator.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Receiving service: compact acceptance truth and independently bounded optional observations. */
final class MessageLab implements AutoCloseable {
    private static final int CLEANUP_BATCH = 100, DIAGNOSTICS_PER_MESSAGE = 64;
    private final Object gate = new Object();
    private final long seed;
    private final MessageSettings settings;
    private final LongSupplier clock, monotonic;
    private final Predicate<Receipt> callback;
    private final Runnable associationMaintenance;
    private final LinkedHashMap<String, Acceptance> acceptances = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> observations = new LinkedHashMap<>();
    private final LinkedHashMap<String, Recent> recent = new LinkedHashMap<>();
    private final Map<String, Set<String>> recordsBySender = new HashMap<>();
    private final Map<String, String> replyIds = new HashMap<>();
    private final Map<String, Receipt> held = new LinkedHashMap<>();
    private final NavigableSet<Entry> actions = new TreeSet<>(Comparator.comparingLong((Entry e) -> e.nextAt).thenComparing(e -> e.id));
    private ScheduledExecutorService ticker;
    private boolean holding, closed;
    private long receiptSequence, acceptedMessages, offered, queued, httpSucceeded, reportAccepted, callbackFailed, duplicates;
    private long expiredAcceptances, expiredObservations, trackingSkipped, droppedReceipts, discardedDiagnostics, evictedRecent;

    MessageLab(long seed, MessageSettings settings, LongSupplier clock, LongSupplier monotonic,
            Predicate<Receipt> callback, Runnable associationMaintenance) {
        this.seed = seed; this.settings = settings; this.clock = clock; this.monotonic = monotonic;
        this.callback = callback; this.associationMaintenance = associationMaintenance;
    }

    void start() {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message Lab closed");
            if (ticker != null) return;
            ticker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "message-lab-actions"); thread.setDaemon(true); return thread;
            });
            ticker.scheduleWithFixedDelay(() -> { associationMaintenance.run(); tick(); }, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    Map<String, Object> accept(Map<String, Object> request, Map<String, Object> sender, String callbackId, MessageContentMode mode) {
        MessageScenario.validateMessage(request);
        if (!sender.keySet().equals(Set.of("workerGroupId", "replicaKey", "workerId", "phone", "country")))
            throw new IllegalArgumentException("Invalid sender fields");
        sender.keySet().forEach(key -> MessageScenario.text(sender, key, 256));
        String id = (String) request.get("messageId"), fingerprint = inputFingerprint(request);
        Receipt receipt = null;
        Map<String, Object> response;
        synchronized (gate) {
            requireOpen();
            long nowNanos = monotonic.getAsLong();
            cleanup(nowNanos);
            Acceptance previous = acceptances.get(id);
            if (previous != null && expired(nowNanos, previous.createdNanos, settings.dedupWindowNanos())) {
                acceptances.remove(id); expiredAcceptances++; previous = null;
            }
            if (previous != null) {
                if (!previous.fingerprint.equals(fingerprint)) throw new IllegalArgumentException("Message identity conflict");
                duplicates++; return previous.response(request);
            }
            MessageInstructions instructions = mode == MessageContentMode.LAB_JSON
                    ? MessageInstructions.parse((String) request.get("body"), seed, id) : null;
            if (acceptances.size() >= settings.maxDedupEntries())
                throw new MessageScenario.CapacityExceeded("Message acceptance window is full");
            // A new acceptance generation cannot inherit an older observation or callback.
            removeRecord(id);
            String adopted = callbackId != null && observations.size() < settings.maxTrackedMessages() ? callbackId : null;
            long now = clock.getAsLong();
            Acceptance acceptance = new Acceptance(fingerprint, Map.copyOf(sender), adopted, now, nowNanos);
            acceptances.put(id, acceptance); acceptedMessages++;
            Map<String, Object> sent = acceptance.sent(request);
            if (adopted != null) {
                Entry entry = new Entry(id, acceptance.sender, adopted, sent, instructions, nowNanos, acceptedMessages);
                observations.put(id, entry); index(sender, id);
                if (instructions != null) { receipt = commit(entry, 7, null, null); scheduleNext(entry, now); }
            } else {
                trackingSkipped++;
            }
            var summary = new LinkedHashMap<>(sent); summary.remove("body");
            recent.put(id, new Recent(Map.copyOf(summary), acceptance.sender, nowNanos, acceptedMessages)); index(sender, id);
            if (recent.size() > settings.maxRecentSentRecords()) {
                removeRecent(recent.firstEntry().getKey()); evictedRecent++;
            }
            response = acceptance.response(request);
        }
        if (receipt != null) offerCommitted(receipt);
        return response;
    }

    Map<String, Object> acceptances(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128))
            throw new IllegalArgumentException("Expected 1..100 message IDs");
        synchronized (gate) {
            requireOpen();
            long now = monotonic.getAsLong();
            var items = new ArrayList<Map<String, Object>>(); var missing = new ArrayList<String>();
            for (String id : new LinkedHashSet<>(ids)) {
                Acceptance entry = acceptances.get(id);
                if (entry == null || expired(now, entry.createdNanos, settings.dedupWindowNanos())) missing.add(id);
                else items.add(Map.of("messageId", id, "inputFingerprint", entry.fingerprint,
                        "sender", entry.sender, "observedAtMillis", entry.observedAtMillis));
            }
            return Map.of("items", items, "missingIds", missing);
        }
    }

    Map<String, Object> act(String group, String replica, String id, String action, Map<String, Object> input) {
        Receipt receipt;
        synchronized (gate) {
            requireOpen(); cleanup(monotonic.getAsLong());
            Entry entry = observations.get(id);
            if (entry != null && expired(monotonic.getAsLong(), entry.createdNanos, settings.receiptWindowNanos())) {
                removeObservation(id); expiredObservations++; entry = null;
            }
            if (entry == null || !entry.sender.get("workerGroupId").equals(group)
                    || !entry.sender.get("replicaKey").equals(replica)) throw new MessageScenario.MissingMessage();
            receipt = action(entry, action, input);
        }
        if (receipt == null) return Map.of("persisted", true, "unchanged", true, "held", false, "callbackQueued", false);
        boolean accepted = offerCommitted(receipt);
        return Map.of("persisted", true, "unchanged", false, "held", receipt.heldOnCommit && receipt.diagnostic.held,
                "callbackQueued", accepted, "receiptId", receipt.id);
    }

    private Receipt action(Entry entry, String action, Map<String, Object> input) {
        int tag = switch (action) { case "read" -> 8; case "reply" -> 9;
            default -> throw new IllegalArgumentException("Unknown recipient action"); };
        String operation = null, reply = null, fingerprint = null;
        if (tag == 9) {
            if (!input.keySet().equals(Set.of("requestId", "text"))) throw new IllegalArgumentException("Invalid reply fields");
            operation = MessageScenario.text(input, "requestId", 128); reply = MessageScenario.text(input, "text", 4096);
            fingerprint = fingerprint("lab-reply/v1", entry.id, reply);
            String previous = replyIds.get(operation);
            if (previous != null) {
                if (!previous.equals(fingerprint)) throw new IllegalArgumentException("Reply identity conflict");
                return null;
            }
            if (replyIds.size() >= MessageScenario.MAX_REPLY_IDS) throw new MessageScenario.CapacityExceeded("Reply operation window is full");
        } else {
            if (!input.isEmpty()) throw new IllegalArgumentException("Recipient action takes an empty object");
            if (entry.stage >= tag) return null;
        }
        if (operation != null) { replyIds.put(operation, fingerprint); entry.replyIds.add(operation); }
        return commit(entry, tag, operation, reply);
    }

    private Receipt commit(Entry entry, int tag, String operation, String reply) {
        long time = Math.max(clock.getAsLong(), ((Number) entry.snapshot.get("observedAtMillis")).longValue() + 1);
        var snapshot = new LinkedHashMap<>(entry.snapshot);
        snapshot.put("status", tag == 7 ? "DELIVERED" : tag == 8 ? "READ" : "REPLIED"); snapshot.put("observedAtMillis", time);
        if (reply != null) { snapshot.put("reply", reply); snapshot.put("replyRequestId", operation); }
        entry.stage = tag; entry.snapshot = Map.copyOf(snapshot);
        String id = "receipt-" + ++receiptSequence;
        boolean retainedHold = holding && held.size() < MessageScenario.MAX_HELD;
        Diagnostic diagnostic = new Diagnostic(id, (String) snapshot.get("status"), time, retainedHold);
        entry.receipts.addLast(diagnostic);
        if (entry.receipts.size() > DIAGNOSTICS_PER_MESSAGE) { entry.receipts.removeFirst(); discardedDiagnostics++; }
        Receipt receipt = new Receipt(id, entry.callbackId, entry.sender, entry.snapshot, holding, diagnostic);
        if (retainedHold) { held.put(id, receipt); entry.heldIds.add(id); }
        else if (holding) { droppedReceipts++; diagnostic.failure = "receipt_hold_full"; }
        return receipt;
    }

    void tick() {
        synchronized (gate) { if (closed) return; cleanup(monotonic.getAsLong()); }
        for (int i = 0; i < 100; i++) {
            Receipt receipt;
            synchronized (gate) {
                if (closed || actions.isEmpty() || actions.first().nextAt > clock.getAsLong()) return;
                Entry entry = actions.pollFirst();
                if (expired(monotonic.getAsLong(), entry.createdNanos, settings.receiptWindowNanos())) {
                    removeObservation(entry.id); expiredObservations++; continue;
                }
                String action = entry.instructions.retained().get(entry.step);
                try {
                    receipt = action(entry, action.equals("read") ? "read" : "reply", action.equals("read") ? Map.of()
                            : Map.of("requestId", "auto-" + UUID.nameUUIDFromBytes((entry.id + "\n" + entry.step)
                                    .getBytes(StandardCharsets.UTF_8)), "text", entry.instructions.reply()));
                    entry.step++; scheduleNext(entry, clock.getAsLong());
                } catch (RuntimeException failure) { entry.planFailure = "business_action_rejected"; continue; }
            }
            if (receipt != null) offerCommitted(receipt);
        }
    }

    private void scheduleNext(Entry entry, long now) {
        if (entry.instructions != null && entry.step < entry.instructions.retained().size()) {
            entry.nextAt = now + entry.instructions.delays().get(entry.step); actions.add(entry);
        }
    }
    private boolean offerCommitted(Receipt receipt) { return !receipt.heldOnCommit && offer(receipt); }
    private boolean offer(Receipt receipt) {
        synchronized (gate) { offered++; }
        boolean accepted = callback.test(receipt);
        synchronized (gate) {
            receipt.diagnostic.callbackQueued |= accepted;
            if (accepted) queued++;
            else { callbackFailed++; droppedReceipts++; receipt.diagnostic.failure = "callback_queue_rejected"; }
        }
        return accepted;
    }
    void callbackCompleted(Receipt receipt, int status, Boolean accepted, String failure) {
        synchronized (gate) {
            Diagnostic diagnostic = receipt.diagnostic;
            diagnostic.attempts++; diagnostic.httpStatus = status; diagnostic.reportAccepted = accepted; diagnostic.failure = failure;
            if (status >= 200 && status < 300) httpSucceeded++; else callbackFailed++;
            if (Boolean.TRUE.equals(accepted)) reportAccepted++;
        }
    }

    Map<String, Object> hold(boolean enabled) {
        synchronized (gate) { requireOpen(); holding = enabled; return Map.of("holding", holding, "held", held.size()); }
    }
    Map<String, Object> release(List<String> ids) {
        List<Receipt> batch;
        synchronized (gate) {
            requireOpen(); long now = monotonic.getAsLong(); cleanup(now);
            if (ids.isEmpty() || ids.size() > MessageScenario.MAX_HELD || ids.stream().anyMatch(id -> id == null || !held.containsKey(id)))
                throw new IllegalArgumentException("Expected 1..10000 existing receipt IDs");
            batch = ids.stream().map(held::get).toList();
            // A bounded maintenance pass may leave expired records later in the
            // map. Explicit IDs must still obey their own fixed deadline.
            for (Receipt receipt : batch) {
                String messageId = (String) receipt.snapshot.get("messageId");
                Entry entry = observations.get(messageId);
                if (entry == null || expired(now, entry.createdNanos, settings.receiptWindowNanos())) {
                    if (entry != null) { removeObservation(messageId); expiredObservations++; }
                    throw new IllegalArgumentException("Receipt observation expired");
                }
            }
            for (Receipt receipt : batch) {
                receipt.diagnostic.held = false; held.remove(receipt.id);
                Entry entry = observations.get(receipt.snapshot.get("messageId"));
                if (entry != null) entry.heldIds.remove(receipt.id);
            }
        }
        return Map.of("offered", batch.size(), "queued", batch.stream().filter(this::offer).count());
    }

    Map<String, Object> page(String group, String replica, int offset, int limit) {
        MessageScenario.pageBounds(offset, limit);
        synchronized (gate) {
            long now = monotonic.getAsLong();
            var rows = new ArrayList<Viewed>();
            if (group != null) {
                // Reuse the stop index; a known Worker read need not walk the Host.
                for (String id : recordsBySender.getOrDefault(senderKey(group, replica), Set.of())) {
                    Entry entry = observations.get(id);
                    if (entry != null && !expired(now, entry.createdNanos, settings.receiptWindowNanos()))
                        rows.add(new Viewed(entry.sequence, id, true));
                    else {
                        Recent summary = recent.get(id);
                        if (summary != null && !expired(now, summary.createdNanos, settings.receiptWindowNanos()))
                            rows.add(new Viewed(summary.sequence, id, false));
                    }
                }
            } else {
                for (Entry entry : observations.values()) if (!expired(now, entry.createdNanos, settings.receiptWindowNanos()))
                    rows.add(new Viewed(entry.sequence, entry.id, true));
                for (var row : recent.entrySet()) {
                    Recent entry = row.getValue();
                    if (!observations.containsKey(row.getKey()) && !expired(now, entry.createdNanos, settings.receiptWindowNanos()))
                        rows.add(new Viewed(entry.sequence, row.getKey(), false));
                }
            }
            rows.sort(Comparator.comparingLong(Viewed::sequence));
            return Map.of("total", rows.size(), "items", rows.stream().skip(offset).limit(limit)
                    .map(row -> row.tracked ? observations.get(row.id).view() : recent.get(row.id).view()).toList(),
                    "acceptedTotal", acceptedMessages, "retentionLimited", true);
        }
    }
    Map<String, Object> metrics() {
        synchronized (gate) {
            var result = new LinkedHashMap<String, Object>();
            int retained = observations.size();
            for (String id : recent.keySet()) if (!observations.containsKey(id)) retained++;
            result.put("messages", retained); result.put("acceptedMessages", acceptedMessages);
            result.put("dedupEntries", acceptances.size()); result.put("trackedMessages", observations.size()); result.put("recentSentRecords", recent.size());
            result.put("held", held.size()); result.put("replyIds", replyIds.size()); result.put("holding", holding);
            result.put("callbackOffered", offered); result.put("callbackQueued", queued); result.put("httpSucceeded", httpSucceeded);
            result.put("reportAccepted", reportAccepted); result.put("callbackFailed", callbackFailed); result.put("duplicateSends", duplicates);
            result.put("scheduled", actions.size()); result.put("trackingSkipped", trackingSkipped); result.put("droppedReceipts", droppedReceipts);
            result.put("expiredAcceptances", expiredAcceptances); result.put("expiredObservations", expiredObservations);
            result.put("discardedReceiptDiagnostics", discardedDiagnostics); result.put("evictedRecentRecords", evictedRecent);
            return result;
        }
    }

    private void cleanup(long now) {
        for (int i = 0; i < CLEANUP_BATCH && !acceptances.isEmpty(); i++) {
            var first = acceptances.firstEntry();
            if (!expired(now, first.getValue().createdNanos, settings.dedupWindowNanos())) break;
            acceptances.pollFirstEntry(); expiredAcceptances++;
        }
        for (int i = 0; i < CLEANUP_BATCH && !observations.isEmpty(); i++) {
            var first = observations.firstEntry();
            if (!expired(now, first.getValue().createdNanos, settings.receiptWindowNanos())) break;
            removeObservation(first.getKey()); expiredObservations++;
        }
        for (int i = 0; i < CLEANUP_BATCH && !recent.isEmpty(); i++) {
            var first = recent.firstEntry();
            if (!expired(now, first.getValue().createdNanos, settings.receiptWindowNanos())) break;
            removeRecent(first.getKey());
        }
    }
    void stop(String group, String replica) {
        synchronized (gate) {
            Set<String> ids = recordsBySender.get(senderKey(group, replica));
            if (ids != null) for (String id : List.copyOf(ids)) removeRecord(id);
        }
    }
    private void removeRecord(String id) { removeObservation(id); removeRecent(id); }
    private void removeObservation(String id) {
        Entry entry = observations.remove(id);
        if (entry == null) return;
        actions.remove(entry); entry.heldIds.forEach(held::remove); entry.replyIds.forEach(replyIds::remove); unindex(entry.sender, id);
    }
    private void removeRecent(String id) { Recent entry = recent.remove(id); if (entry != null) unindex(entry.sender, id); }
    private void index(Map<String, Object> sender, String id) {
        recordsBySender.computeIfAbsent(senderKey(sender), ignored -> new HashSet<>()).add(id);
    }
    private void unindex(Map<String, Object> sender, String id) {
        if (observations.containsKey(id) || recent.containsKey(id)) return;
        String key = senderKey(sender); Set<String> ids = recordsBySender.get(key);
        if (ids != null) { ids.remove(id); if (ids.isEmpty()) recordsBySender.remove(key); }
    }
    private static String senderKey(Map<String, Object> sender) { return senderKey((String) sender.get("workerGroupId"), (String) sender.get("replicaKey")); }
    private static String senderKey(String group, String replica) { return group.length() + ":" + group + replica; }
    private static boolean expired(long now, long created, long window) { return now - created >= window; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Message Lab closed"); }
    @Override public void close() {
        ScheduledExecutorService closing;
        synchronized (gate) {
            closed = true; actions.clear(); held.clear(); observations.clear(); recent.clear(); recordsBySender.clear();
            replyIds.clear(); acceptances.clear(); closing = ticker;
        }
        if (closing != null) closing.shutdownNow();
    }
    static String inputFingerprint(Map<String, Object> request) {
        return fingerprint("lab-message-input/v1", (String) request.get("campaignId"), (String) request.get("messageId"),
                (String) request.get("country"), (String) request.get("recipientId"), (String) request.get("body"));
    }
    private static String fingerprint(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Acceptance(String fingerprint, Map<String, Object> sender, String callbackId, long observedAtMillis, long createdNanos) {
        Map<String, Object> sent(Map<String, Object> request) {
            var sent = new LinkedHashMap<>(request); sent.put("status", "SENT"); sent.put("workerId", sender.get("workerId"));
            sent.put("phone", sender.get("phone")); sent.put("observedAtMillis", observedAtMillis); return Map.copyOf(sent);
        }
        Map<String, Object> response(Map<String, Object> request) {
            var result = new LinkedHashMap<String, Object>(); result.put("snapshot", sent(request)); result.put("callbackId", callbackId); return result;
        }
    }
    private record Viewed(long sequence, String id, boolean tracked) {}
    private record Recent(Map<String, Object> sent, Map<String, Object> sender, long createdNanos, long sequence) {
        Map<String, Object> view() {
            var result = new LinkedHashMap<>(sent); result.put("trackingAvailable", false);
            result.put("plan", Map.of()); result.put("receipts", List.of()); return result;
        }
    }
    record Receipt(String id, String callbackId, Map<String, Object> sender, Map<String, Object> snapshot,
            boolean heldOnCommit, Diagnostic diagnostic) {}
    private static final class Diagnostic {
        final String id, status; final long time;
        boolean held, callbackQueued; Boolean reportAccepted; int attempts, httpStatus; String failure;
        Diagnostic(String id, String status, long time, boolean held) { this.id = id; this.status = status; this.time = time; this.held = held; }
        Map<String, Object> view() {
            var result = new LinkedHashMap<String, Object>();
            result.put("receiptId", id); result.put("status", status); result.put("observedAtMillis", time); result.put("held", held);
            result.put("callbackQueued", callbackQueued); result.put("attempts", attempts); result.put("httpStatus", httpStatus);
            result.put("reportAccepted", reportAccepted); result.put("failure", failure); return result;
        }
    }
    private static final class Entry {
        final String id, callbackId; final Map<String, Object> sender; final MessageInstructions instructions;
        final long createdNanos, sequence;
        final ArrayDeque<Diagnostic> receipts = new ArrayDeque<>();
        final Set<String> heldIds = new HashSet<>(), replyIds = new HashSet<>();
        Map<String, Object> snapshot; int stage = 6, step; long nextAt; String planFailure;
        Entry(String id, Map<String, Object> sender, String callbackId,
                Map<String, Object> sent, MessageInstructions instructions, long createdNanos, long sequence) {
            this.id = id; this.sender = sender; this.callbackId = callbackId; this.instructions = instructions;
            this.snapshot = sent; this.createdNanos = createdNanos; this.sequence = sequence;
        }
        Map<String, Object> view() {
            var result = new LinkedHashMap<>(snapshot); var plan = new LinkedHashMap<String, Object>();
            if (instructions != null) {
                plan.put("requested", instructions.requested()); plan.put("retained", instructions.retained());
                plan.put("delayMs", instructions.delays()); plan.put("droppedLast", instructions.droppedLast()); plan.put("completedSteps", step);
                plan.put("failure", planFailure); plan.put("nextAtMillis", step < instructions.retained().size() && planFailure == null ? nextAt : null);
            }
            result.put("trackingAvailable", true); result.put("plan", plan); result.put("receipts", receipts.stream().map(Diagnostic::view).toList()); return result;
        }
    }
}
