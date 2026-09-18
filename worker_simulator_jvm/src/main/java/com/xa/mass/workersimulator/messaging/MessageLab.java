package com.xa.mass.workersimulator.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Receiving business service. Owns facts and plans, and knows no Worker Reporter. */
final class MessageLab implements AutoCloseable {
    private final Object gate = new Object();
    private final long seed;
    private final LongSupplier clock;
    private final Predicate<Receipt> callback;
    private final Map<String, Entry> messages = new LinkedHashMap<>();
    private final Map<String, String> campaigns = new HashMap<>(), replyIds = new HashMap<>();
    private final Map<String, Receipt> held = new LinkedHashMap<>();
    private final PriorityQueue<Entry> actions = new PriorityQueue<>(Comparator.comparingLong(e -> e.nextAt));
    private ScheduledExecutorService ticker;
    private boolean holding, closed;
    private long receiptSequence, offered, queued, httpSucceeded, reportAccepted, callbackFailed, duplicates;

    MessageLab(long seed, LongSupplier clock, Predicate<Receipt> callback) {
        this.seed = seed; this.clock = clock; this.callback = callback;
    }

    void start() {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message Lab closed");
            if (ticker != null) return;
            ticker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "message-lab-actions"); thread.setDaemon(true); return thread;
            });
            ticker.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    Map<String, Object> accept(Map<String, Object> request, Map<String, Object> sender, String callbackId) {
        MessageScenario.validateMessage(request);
        if (!sender.keySet().equals(Set.of("workerGroupId", "replicaKey", "workerId", "phone", "country")))
            throw new IllegalArgumentException("Invalid sender fields");
        sender.keySet().forEach(key -> MessageScenario.text(sender, key, 256));
        String id = (String) request.get("messageId"), campaign = (String) request.get("campaignId");
        Receipt receipt;
        Map<String, Object> response;
        synchronized (gate) {
            requireOpen();
            Entry previous = messages.get(id);
            if (previous != null) {
                if (!previous.request.equals(request)) throw new IllegalArgumentException("Message identity conflict");
                duplicates++; return previous.response();
            }
            MessageInstructions instructions = MessageInstructions.parse((String) request.get("body"), seed, id);
            if (!sender.get("country").equals(request.get("country"))) throw new IllegalArgumentException("Wrong sender country");
            if (messages.size() == MessageScenario.MAX_MESSAGES
                    || !campaigns.containsKey(campaign) && campaigns.size() == MessageScenario.MAX_CAMPAIGNS)
                throw new IllegalStateException("Message channel capacity exhausted");
            if (campaigns.containsKey(campaign) && !campaigns.get(campaign).equals(request.get("country")))
                throw new IllegalArgumentException("Campaign country conflict");
            checkReceiptCapacity();
            long now = clock.getAsLong();
            var sent = new LinkedHashMap<>(request);
            sent.put("status", "SENT"); sent.put("workerId", sender.get("workerId")); sent.put("phone", sender.get("phone"));
            sent.put("observedAtMillis", now);
            Entry entry = new Entry(Map.copyOf(request), Map.copyOf(sender), callbackId, Map.copyOf(sent), instructions);
            campaigns.put(campaign, (String) request.get("country")); messages.put(id, entry);
            receipt = commit(entry, 7, null, null);
            scheduleNext(entry, now);
            response = entry.response();
        }
        offerCommitted(receipt);
        return response;
    }

    Map<String, Object> act(String group, String replica, String id, String action, Map<String, Object> input) {
        Receipt receipt;
        synchronized (gate) {
            requireOpen();
            Entry entry = messages.get(id);
            if (entry == null || !entry.sender.get("workerGroupId").equals(group)
                    || !entry.sender.get("replicaKey").equals(replica)) throw new MessageScenario.MissingMessage();
            receipt = action(entry, action, input);
        }
        if (receipt == null) return Map.of("persisted", true, "unchanged", true, "held", false, "callbackQueued", false);
        boolean accepted = offerCommitted(receipt);
        return Map.of("persisted", true, "unchanged", false, "held", receipt.heldOnCommit,
                "callbackQueued", accepted, "receiptId", receipt.id);
    }

    private Receipt action(Entry entry, String action, Map<String, Object> input) {
        int tag = switch (action) { case "read" -> 8; case "reply" -> 9;
            default -> throw new IllegalArgumentException("Unknown recipient action"); };
        String operation = null, reply = null, fingerprint = null;
        if (tag == 9) {
            if (!input.keySet().equals(Set.of("requestId", "text"))) throw new IllegalArgumentException("Invalid reply fields");
            operation = MessageScenario.text(input, "requestId", 128); reply = MessageScenario.text(input, "text", 4096);
            fingerprint = fingerprint(entry.request.get("messageId") + "\n" + reply);
            String previous = replyIds.get(operation);
            if (previous != null) {
                if (!previous.equals(fingerprint)) throw new IllegalArgumentException("Reply identity conflict");
                return null;
            }
            if (replyIds.size() == MessageScenario.MAX_REPLY_IDS) throw new IllegalStateException("Reply identity capacity exhausted");
        } else {
            if (!input.isEmpty()) throw new IllegalArgumentException("Recipient action takes an empty object");
            if (entry.stage >= tag) return null;
        }
        checkReceiptCapacity();
        if (operation != null) replyIds.put(operation, fingerprint);
        return commit(entry, tag, operation, reply);
    }

    private Receipt commit(Entry entry, int tag, String operation, String reply) {
        long time = Math.max(clock.getAsLong(), ((Number) entry.snapshot.get("observedAtMillis")).longValue() + 1);
        var snapshot = new LinkedHashMap<>(entry.snapshot);
        snapshot.put("status", tag == 7 ? "DELIVERED" : tag == 8 ? "READ" : "REPLIED"); snapshot.put("observedAtMillis", time);
        if (reply != null) { snapshot.put("reply", reply); snapshot.put("replyRequestId", operation); }
        entry.stage = tag; entry.snapshot = Map.copyOf(snapshot);
        String id = "receipt-" + ++receiptSequence;
        Diagnostic diagnostic = new Diagnostic(id, (String) snapshot.get("status"), time, holding);
        entry.receipts.add(diagnostic);
        Receipt receipt = new Receipt(id, entry.callbackId, entry.sender, entry.snapshot, holding, diagnostic);
        if (holding) held.put(id, receipt);
        return receipt;
    }

    // One node per message, at most 100 actions per tick. Business time never waits for HTTP.
    void tick() {
        for (int i = 0; i < 100; i++) {
            Receipt receipt;
            synchronized (gate) {
                if (closed || actions.isEmpty() || actions.peek().nextAt > clock.getAsLong()) return;
                Entry entry = actions.remove();
                String action = entry.instructions.retained().get(entry.step);
                try {
                    receipt = action(entry, action.equals("read") ? "read" : "reply", action.equals("read") ? Map.of()
                            : Map.of("requestId", "auto-" + UUID.nameUUIDFromBytes((entry.request.get("messageId") + "\n" + entry.step)
                                    .getBytes(StandardCharsets.UTF_8)), "text", entry.instructions.reply()));
                    entry.step++;
                    scheduleNext(entry, clock.getAsLong());
                } catch (RuntimeException failure) {
                    entry.planFailure = "business_action_rejected";
                    continue;
                }
            }
            if (receipt != null) offerCommitted(receipt);
        }
    }

    private void scheduleNext(Entry entry, long now) {
        if (entry.step < entry.instructions.retained().size()) {
            entry.nextAt = now + entry.instructions.delays().get(entry.step); actions.add(entry);
        }
    }

    private boolean offerCommitted(Receipt receipt) {
        // A concurrent release owns the offer for a held receipt, even if it already removed it.
        return !receipt.heldOnCommit && offer(receipt);
    }
    private boolean offer(Receipt receipt) {
        synchronized (gate) {
            offered++;
        }
        boolean accepted = callback.test(receipt);
        synchronized (gate) {
            receipt.diagnostic.callbackQueued |= accepted;
            if (accepted) queued++;
            else { callbackFailed++; receipt.diagnostic.failure = "callback_queue_rejected"; }
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
            requireOpen();
            if (ids.isEmpty() || ids.size() > MessageScenario.MAX_HELD || ids.stream().anyMatch(id -> id == null || !held.containsKey(id)))
                throw new IllegalArgumentException("Expected 1..10000 existing receipt IDs");
            batch = ids.stream().map(held::get).toList();
            batch.forEach(receipt -> receipt.diagnostic.held = false);
            ids.forEach(held::remove);
        }
        long accepted = batch.stream().filter(this::offer).count();
        return Map.of("offered", batch.size(), "queued", accepted);
    }
    Map<String, Object> page(String group, String replica, int offset, int limit) {
        MessageScenario.pageBounds(offset, limit);
        synchronized (gate) {
            var entries = messages.values().stream().filter(e -> group == null
                    || e.sender.get("workerGroupId").equals(group) && e.sender.get("replicaKey").equals(replica)).toList();
            return Map.of("total", entries.size(), "items", entries.stream().skip(offset).limit(limit).map(Entry::view).toList());
        }
    }
    Map<String, Object> metrics() {
        synchronized (gate) {
            var result = new LinkedHashMap<String, Object>();
            result.put("messages", messages.size()); result.put("campaigns", campaigns.size()); result.put("held", held.size());
            result.put("holding", holding); result.put("callbackOffered", offered); result.put("callbackQueued", queued);
            result.put("httpSucceeded", httpSucceeded); result.put("reportAccepted", reportAccepted);
            result.put("callbackFailed", callbackFailed); result.put("duplicateSends", duplicates); result.put("scheduled", actions.size());
            return result;
        }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Message Lab closed"); }
    private void checkReceiptCapacity() {
        if (holding && held.size() == MessageScenario.MAX_HELD) throw new IllegalStateException("Receipt hold capacity exhausted");
    }
    @Override public void close() {
        ScheduledExecutorService closing;
        synchronized (gate) { closed = true; actions.clear(); held.clear(); closing = ticker; }
        if (closing != null) closing.shutdownNow();
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        final Map<String, Object> request, sender, sent; final String callbackId; final MessageInstructions instructions;
        final List<Diagnostic> receipts = new ArrayList<>();
        Map<String, Object> snapshot; int stage = 6, step; long nextAt; String planFailure;
        Entry(Map<String, Object> request, Map<String, Object> sender, String callbackId,
                Map<String, Object> sent, MessageInstructions instructions) {
            this.request = request; this.sender = sender; this.callbackId = callbackId; this.sent = sent;
            this.snapshot = sent; this.instructions = instructions;
        }
        Map<String, Object> response() { return Map.of("snapshot", sent, "callbackId", callbackId); }
        Map<String, Object> view() {
            var result = new LinkedHashMap<>(snapshot);
            var plan = new LinkedHashMap<String, Object>();
            plan.put("requested", instructions.requested()); plan.put("retained", instructions.retained());
            plan.put("delayMs", instructions.delays()); plan.put("droppedLast", instructions.droppedLast()); plan.put("completedSteps", step);
            plan.put("failure", planFailure); plan.put("nextAtMillis", step < instructions.retained().size() && planFailure == null ? nextAt : null);
            result.put("plan", plan); result.put("receipts", receipts.stream().map(Diagnostic::view).toList());
            return result;
        }
    }
}
