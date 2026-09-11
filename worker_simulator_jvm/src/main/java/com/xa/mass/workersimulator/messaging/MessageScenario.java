package com.xa.mass.workersimulator.messaging;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Finite simulated sending channel and recipient actions. No platform owner or replay client. */
public final class MessageScenario implements AutoCloseable {
    public static final String SEND_EVENT = "extension.worker.message.send";
    public static final int MAX_MESSAGES = 50_000, MAX_CAMPAIGNS = 50, MAX_HELD = 10_000, MAX_REPLY_IDS = 100_000;
    private final Object gate = new Object();
    private final Map<String, Sender> senders = new LinkedHashMap<>();
    private final Map<String, Entry> messages = new LinkedHashMap<>();
    private final Map<String, String> campaigns = new HashMap<>();
    private final Map<String, String> replyIds = new HashMap<>();
    private final Map<String, Receipt> held = new LinkedHashMap<>();
    private boolean holding, closed;
    private long receiptSequence, published, sendAccepted, sendFailed, duplicates;

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
        if (!request.keySet().equals(Set.of("campaignId", "messageId", "country", "recipientId", "body")))
            throw new IllegalArgumentException("Invalid send fields");
        String id = text(request, "messageId", 128), campaign = text(request, "campaignId", 128);
        text(request, "recipientId", 128); text(request, "body", 4096);
        String country = text(request, "country", 2);
        synchronized (gate) {
            Entry previous = messages.get(id);
            if (previous != null) {
                if (!previous.request.equals(request)) throw new IllegalArgumentException("Message identity conflict");
                duplicates++;
                return previous.snapshot;
            }
            if (closed || !sender.accepting || !"RUNNING".equals(sender.runtimeState.get()))
                throw new IllegalStateException("Sender is stopped");
            Map<String, String> properties = sender.properties.get();
            if (!properties.get("country").equals(country)) throw new IllegalArgumentException("Wrong sender country");
            String worker = sender.workerId.get();
            if (worker == null || worker.isBlank()) throw new IllegalStateException("Sender identity unavailable");
            if (messages.size() == MAX_MESSAGES || !campaigns.containsKey(campaign) && campaigns.size() == MAX_CAMPAIGNS)
                throw new IllegalStateException("Message channel capacity exhausted");
            String existingCountry = campaigns.get(campaign);
            if (existingCountry != null && !existingCountry.equals(country)) throw new IllegalArgumentException("Campaign country conflict");
            campaigns.put(campaign, country);
            Entry entry = new Entry(Map.copyOf(request), sender, reporter);
            var snapshot = new LinkedHashMap<String, Object>(request);
            snapshot.put("status", "SENT"); snapshot.put("workerId", worker); snapshot.put("phone", properties.get("phone"));
            snapshot.put("observedAtMillis", System.currentTimeMillis());
            entry.snapshot = Map.copyOf(snapshot);
            messages.put(id, entry);
            sender.messages.add(entry);
            return entry.snapshot;
        }
    }

    public Map<String, Object> act(Sender sender, String id, String action, Map<String, Object> input) {
        Receipt receipt;
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message channel closed");
            Entry entry = messages.get(id);
            if (entry == null || entry.sender != sender) throw new MissingMessage();
            int tag = switch (action) { case "deliver" -> 7; case "read" -> 8; case "reply" -> 9;
                default -> throw new IllegalArgumentException("Unknown recipient action"); };
            String operation = null, fingerprint = null, reply = null;
            if (tag == 9) {
                if (!input.keySet().equals(Set.of("requestId", "text"))) throw new IllegalArgumentException("Invalid reply fields");
                operation = text(input, "requestId", 128); reply = text(input, "text", 4096);
                fingerprint = fingerprint(id + "\n" + reply);
                String previous = replyIds.get(operation);
                if (previous != null) {
                    if (!previous.equals(fingerprint)) throw new IllegalArgumentException("Reply identity conflict");
                    return Map.of("persisted", true, "unchanged", true, "held", false, "sendAccepted", false);
                }
                if (entry.stage < 7) throw new IllegalStateException("Deliver the message before replying");
                if (replyIds.size() == MAX_REPLY_IDS) throw new IllegalStateException("Reply identity capacity exhausted");
            } else {
                if (!input.isEmpty()) throw new IllegalArgumentException("Recipient action takes an empty object");
                if (entry.stage >= tag) return Map.of("persisted", true, "unchanged", true, "held", false, "sendAccepted", false);
                if (tag == 8 && entry.stage < 7) throw new IllegalStateException("Deliver the message before reading");
            }
            if (holding && held.size() == MAX_HELD) throw new IllegalStateException("Receipt hold capacity exhausted");
            long time = Math.max(System.currentTimeMillis(), ((Number) entry.snapshot.get("observedAtMillis")).longValue() + 1);
            var snapshot = new LinkedHashMap<>(entry.snapshot);
            snapshot.put("status", tag == 7 ? "DELIVERED" : tag == 8 ? "READ" : "REPLIED");
            snapshot.put("observedAtMillis", time);
            if (reply != null) { snapshot.put("reply", reply); snapshot.put("replyRequestId", operation); replyIds.put(operation, fingerprint); }
            entry.stage = tag; entry.snapshot = Map.copyOf(snapshot);
            receipt = new Receipt("receipt-" + ++receiptSequence, entry, tag, time, entry.snapshot);
            if (holding) {
                held.put(receipt.id, receipt);
                return Map.of("persisted", true, "unchanged", false, "held", true, "sendAccepted", false, "receiptId", receipt.id);
            }
        }
        return Map.of("persisted", true, "unchanged", false, "held", false, "sendAccepted", publish(receipt), "receiptId", receipt.id);
    }

    private boolean publish(Receipt receipt) {
        WorkerOutcomeReporter reporter;
        synchronized (gate) { reporter = closed ? null : receipt.entry.reporter; }
        boolean accepted = false;
        try { accepted = reporter != null && reporter.report(receipt.tag, receipt.time, Jsons.toJson(receipt.snapshot)); }
        catch (RuntimeException ignored) { /* Local business fact remains committed; no retry. */ }
        synchronized (gate) {
            published++; if (accepted) sendAccepted++; else sendFailed++;
        }
        return accepted;
    }
    public Map<String, Object> hold(boolean enabled) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message channel closed");
            holding = enabled; return Map.of("holding", holding, "held", held.size());
        }
    }
    public Map<String, Object> release(List<String> ids) {
        List<Receipt> batch;
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message channel closed");
            if (ids.isEmpty() || ids.size() > MAX_HELD || ids.stream().anyMatch(id -> id == null || !held.containsKey(id)))
                throw new IllegalArgumentException("Expected 1..10000 existing receipt IDs");
            batch = ids.stream().map(held::get).toList();
            ids.forEach(held::remove);
        }
        long accepted = batch.stream().filter(this::publish).count();
        return Map.of("published", batch.size(), "sendAccepted", accepted);
    }
    public Map<String, Object> page(int offset, int limit) {
        pageBounds(offset, limit);
        synchronized (gate) {
            return Map.of("total", messages.size(), "items", messages.values().stream().skip(offset).limit(limit)
                    .map(e -> e.snapshot).toList());
        }
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
    public Map<String, Object> page(Sender sender, int offset, int limit) {
        pageBounds(offset, limit);
        synchronized (gate) {
            return Map.of("total", sender.messages.size(), "items", sender.messages.stream().skip(offset).limit(limit)
                    .map(entry -> entry.snapshot).toList());
        }
    }
    public Map<String, Object> metrics() {
        synchronized (gate) {
            return Map.of("messages", messages.size(), "campaigns", campaigns.size(), "held", held.size(),
                    "holding", holding, "published", published, "sendAccepted", sendAccepted, "sendFailed", sendFailed,
                    "duplicateSends", duplicates, "reporters", messages.values().stream().filter(e -> e.reporter != null).count());
        }
    }
    public void stop(Sender sender) {
        synchronized (gate) {
            sender.accepting = false;
            sender.messages.forEach(entry -> entry.reporter = null);
        }
    }
    public void start(Sender sender) {
        synchronized (gate) {
            if (closed) throw new IllegalStateException("Message channel closed");
            sender.accepting = true;
        }
    }
    public boolean startStillRequested(Sender sender) {
        synchronized (gate) { return !closed && sender.accepting; }
    }
    @Override public void close() {
        synchronized (gate) {
            closed = true; held.clear();
            messages.values().forEach(entry -> entry.reporter = null);
        }
    }
    public static String text(Map<String, Object> input, String key, int maximum) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private static void pageBounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static final class MissingMessage extends RuntimeException {}
    public static final class Sender {
        final String group, replica;
        final Supplier<Map<String, String>> properties;
        final Supplier<String> workerId, runtimeState;
        final List<Entry> messages = new ArrayList<>();
        boolean accepting = true;
        Sender(String group, String replica, Supplier<Map<String, String>> properties, Supplier<String> workerId, Supplier<String> runtimeState) {
            this.group = group; this.replica = replica; this.properties = properties;
            this.workerId = workerId; this.runtimeState = runtimeState;
        }
    }
    private static final class Entry {
        final Map<String, Object> request;
        final Sender sender;
        WorkerOutcomeReporter reporter;
        Map<String, Object> snapshot;
        int stage = 6;
        Entry(Map<String, Object> request, Sender sender, WorkerOutcomeReporter reporter) {
            this.request = request; this.sender = sender; this.reporter = reporter;
        }
    }
    private record Receipt(String id, Entry entry, int tag, long time, Map<String, Object> snapshot) {}
}
