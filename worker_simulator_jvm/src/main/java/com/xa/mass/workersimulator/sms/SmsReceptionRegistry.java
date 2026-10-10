package com.xa.mass.workersimulator.sms;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Business state of one finite simulator run. No Kernel state or delivery retries. */
public final class SmsReceptionRegistry {
    public static final int MAX_ASSOCIATIONS = 50_000;
    public static final int MAX_SMS = 100_000;
    public static final int PER_NUMBER = 64;
    private final Clock clock;
    private final int associationLimit;
    private final int smsLimit;
    private final Map<String, Sim> sims = new ConcurrentHashMap<>();
    private final List<Sim> devices = new ArrayList<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Entry> history = new LinkedHashMap<>();
    private final Object historyGate = new Object(), expiryGate = new Object();
    private final PriorityQueue<Entry> expirations = new PriorityQueue<>(Comparator.comparingLong(e -> e.leaseUntil));
    private long sequence;
    private final Map<String, String> smsIdentities = new LinkedHashMap<>();
    private final Object admission = new Object();
    private final Object smsAdmission = new Object();
    private final LongAdder matched = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder unmatched = new LongAdder();
    private final LongAdder noListeners = new LongAdder();
    private final LongAdder reportAccepted = new LongAdder();
    private final LongAdder reportFailed = new LongAdder();
    private final LongAdder leaseExecutions = new LongAdder();
    private volatile boolean closed;

    public SmsReceptionRegistry() { this(Clock.systemUTC(), MAX_ASSOCIATIONS, MAX_SMS); }
    SmsReceptionRegistry(Clock clock, int associationLimit, int smsLimit) {
        this.clock = clock;
        this.associationLimit = associationLimit;
        this.smsLimit = smsLimit;
    }

    public Sim addSim(String groupId, String replicaKey, Supplier<Map<String, String>> properties,
                      Supplier<String> workerId, Supplier<String> runtimeState, BooleanSupplier desiredRunning) {
        Sim sim = new Sim(groupId, replicaKey, properties, workerId, runtimeState, desiredRunning);
        if (sims.putIfAbsent(properties.get().get("phone"), sim) != null)
            throw new IllegalArgumentException("Duplicate number");
        devices.add(sim);
        return sim;
    }

    /** Host serializes validated inventory commits. No I/O or publication in this gate. */
    public void updateProperties(Sim sim, Map<String, String> replacement, Runnable install) {
        synchronized (sim) {
            String oldPhone = sim.properties.get().get("phone");
            String newPhone = replacement.get("phone");
            if (!oldPhone.equals(newPhone)) {
                sims.remove(oldPhone, sim);
                interrupt(sim, clock.millis());
            }
            install.run();
            sims.put(newPhone, sim);
        }
    }

    public Map<String, Object> lease(Sim sim, Map<String, Object> request, WorkerOutcomeReporter reporter) {
        leaseExecutions.increment();
        String id = string(request, "messageId");
        String application = string(request, "applicationId");
        String country = string(request, "country");
        long seconds = number(request, "leaseSeconds");
        long deadline = number(request, "setupDeadline");
        if (seconds < 1 || seconds > 300)
            throw new IllegalArgumentException("Invalid lease window or country");
        List<Template> templates = templates(request);
        Map<String, Object> specification = Map.of("application", application, "country", country,
                "seconds", seconds, "deadline", deadline, "templates", templates);
        expire();
        synchronized (admission) {
            Entry previous = entries.get(id);
            if (previous == null) synchronized (historyGate) { previous = history.get(id); }
            if (previous != null) {
                if (!previous.specification.equals(specification)) throw new IllegalArgumentException("Association conflict");
                return previous.snapshot;
            }
            long now = clock.millis();
            if (closed || entries.size() >= associationLimit) return rejection(id, "Association capacity exhausted");
            if (now >= deadline) return rejection(id, "Establishment deadline elapsed");
            String workerId = sim.workerId.get();
            if (workerId == null || workerId.isBlank()) return rejection(id, "Worker identity unavailable");
            synchronized (sim) {
                Map<String, String> properties = sim.properties.get();
                if (!properties.get("country").equals(country)) throw new IllegalArgumentException("Wrong SIM country");
                if (!sim.accepting || !"RUNNING".equals(sim.runtimeState.get()))
                    return rejection(id, "Worker is stopped");
                if (sim.active.size() >= PER_NUMBER) return rejection(id, "Number association capacity exhausted");
                Entry entry = new Entry(id, application, specification, sequence++, sim,
                        templates, now, now + seconds * 1000, workerId, reporter, properties);
                sim.active.put(id, entry);
                synchronized (expiryGate) { expirations.add(entry); entry.queued = true; }
                entries.put(id, entry);
                return entry.snapshot;
            }
        }
    }

    public Map<String, Object> receive(Sim sim, String smsId, String text) {
        return receive(sim, null, smsId, text);
    }

    public Map<String, Object> receive(Sim sim, String expectedPhone, String smsId, String text) {
        if (smsId == null || smsId.isBlank() || smsId.length() > 128
                || text == null || text.isBlank() || text.length() > 1024)
            throw new IllegalArgumentException("Invalid simulated SMS");
        long now = clock.millis();
        List<Publication> publications = new ArrayList<>();
        Entry winner = null;
        Template winningTemplate = null;
        synchronized (sim) {
            String phone = sim.properties.get().get("phone");
            // An explicit old address cannot consume a dedup identity or reach its next occupant.
            if (sims.get(phone) != sim || expectedPhone != null && !expectedPhone.equals(phone))
                throw new IllegalArgumentException("SMS phone does not match the target device");
            String digest = fingerprint(phone + "\n" + text);
            synchronized (smsAdmission) {
                if (closed) throw new IllegalStateException("Simulator closed");
                String previous = smsIdentities.get(smsId);
                if (previous != null) {
                    if (!previous.equals(digest)) throw new IllegalArgumentException("SMS identity conflict");
                    duplicates.increment();
                    return Map.of("status", "DUPLICATE", "smsId", smsId);
                }
                if (smsIdentities.size() >= smsLimit) smsIdentities.remove(smsIdentities.keySet().iterator().next());
                smsIdentities.put(smsId, digest);
            }
            if (!"RUNNING".equals(sim.runtimeState.get())) interrupt(sim, now);
            else expire(sim, now);
            for (Entry entry : sim.active.values()) {
                if (now < entry.startedAt) continue;
                for (Template template : entry.templates) {
                    if (!template.matches(text)) continue;
                    if (winningTemplate == null || template.priority > winningTemplate.priority
                            || template.priority == winningTemplate.priority && (entry.sequence < winner.sequence
                            || entry.sequence == winner.sequence && entry.id.compareTo(winner.id) < 0)) {
                        winner = entry;
                        winningTemplate = template;
                    }
                }
            }
            if (winner == null) {
                if (sim.active.isEmpty()) noListeners.increment(); else unmatched.increment();
            } else {
                Map<String, Object> sms = new LinkedHashMap<>();
                sms.put("smsId", smsId);
                sms.put("text", text);
                sms.put("receivedAt", now);
                sms.put("templateId", winningTemplate.id);
                if (!winningTemplate.any) sms.put("code", text.substring(winningTemplate.prefix.length()));
                publications.add(received(winner, now, sms));
                matched.increment();
            }
        }
        publications.forEach(this::publish);
        return winner == null ? Map.of("status", "IGNORED", "smsId", smsId)
                : Map.of("status", "MATCHED", "smsId", smsId, "messageId", winner.id,
                        "templateId", winningTemplate.id);
    }

    public void expire() {
        long now = clock.millis();
        for (int i = 0; i < 512; i++) {
            Entry entry;
            synchronized (expiryGate) {
                entry = expirations.peek();
                if (entry == null || entry.leaseUntil > now) return;
                expirations.remove(); entry.queued = false;
            }
            synchronized (entry.sim) { finish(entry, "EXPIRED", now); }
        }
    }

    private void expire(Sim sim, long now) {
        for (Entry entry : List.copyOf(sim.active.values()))
            if (now >= entry.leaseUntil) finish(entry, "EXPIRED", now);
    }

    private Publication received(Entry entry, long now, Map<String, Object> sms) {
        var next = new LinkedHashMap<>(entry.snapshot);
        next.put("status", "RECEIVED");
        next.put("sms", Map.copyOf(sms));
        next.put("revision", ++entry.revision);
        entry.snapshot = Map.copyOf(next);
        entry.reportTime = Math.max(now, entry.reportTime + 1);
        return new Publication(entry, entry.reporter, entry.reportTime, entry.snapshot);
    }

    private void finish(Entry entry, String status, long now) {
        if (entry.sim.active.remove(entry.id) == null) return;
        entries.remove(entry.id, entry);
        entry.reporter = null;
        synchronized (expiryGate) {
            if (entry.queued) { expirations.remove(entry); entry.queued = false; }
        }
        var next = new LinkedHashMap<>(entry.snapshot);
        next.put("trackingStatus", status);
        next.put("endedAt", now);
        entry.snapshot = Map.copyOf(next);
        synchronized (historyGate) {
            if (history.size() == associationLimit) history.pollFirstEntry();
            history.put(entry.id, entry);
        }
    }

    private void publish(Publication publication) {
        if (publication == null) return;
        boolean accepted = false;
        try {
            accepted = publication.reporter != null && publication.reporter.report(9, publication.time,
                    Jsons.toJson(publication.snapshot));
        } catch (RuntimeException ignored) {
            // A failed observation never forwards this SMS to a different association.
        }
        publication.entry.reportAccepted = accepted;
        if (accepted) reportAccepted.increment(); else reportFailed.increment();
    }

    public void close() {
        synchronized (admission) { closed = true; }
        for (Sim sim : devices) synchronized (sim) {
            sim.accepting = false;
            interrupt(sim, clock.millis());
        }
    }

    /** Admission closes before the Host revokes the SDK run; no synthetic ending Report. */
    public void stop(Sim sim) {
        synchronized (sim) {
            sim.accepting = false;
            interrupt(sim, clock.millis());
        }
    }

    public void beginStart(Sim sim) {
        synchronized (sim) {
            if (closed) throw new IllegalStateException("Simulator closed");
            if (sim.starting) throw new IllegalStateException("Worker start already in progress");
            sim.starting = true;
            sim.accepting = true;
        }
    }

    public boolean startStillRequested(Sim sim) {
        synchronized (sim) { return sim.accepting && !closed; }
    }

    public void endStart(Sim sim) {
        synchronized (sim) { sim.starting = false; }
    }

    private void interrupt(Sim sim, long now) {
        for (Entry entry : List.copyOf(sim.active.values())) finish(entry, "INTERRUPTED", now);
    }

    public List<Sim> devices() { return List.copyOf(devices); }

    public Map<String, Object> inventory(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
        List<Map<String, Object>> page = devices.stream().skip(offset).limit(limit).map(sim -> {
            Map<String, String> properties = sim.properties.get();
            String workerId = Objects.toString(sim.workerId.get(), "");
            String state = sim.runtimeState.get();
            int active;
            synchronized (sim) { active = sim.active.size(); }
            return Map.<String, Object>of("workerGroupId", sim.groupId, "replicaKey", sim.replicaKey,
                    "desiredRunning", sim.desiredRunning.getAsBoolean(), "phone", properties.get("phone"), "country", properties.get("country"),
                    "workerId", workerId, "runtimeState", state, "activeAssociations", active);
        }).toList();
        return Map.of("total", devices.size(), "offset", offset, "limit", limit, "items", page);
    }

    public Map<String, Object> records(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
        List<Entry> page;
        int total;
        List<Entry> retained = new ArrayList<>(entries.values());
        synchronized (historyGate) { retained.addAll(history.values()); }
        retained = retained.stream().distinct().sorted(Comparator.comparingLong(e -> e.sequence)).toList();
        total = retained.size();
        int from = Math.min(offset, total);
        page = retained.subList(from, from + Math.min(limit, total - from));
        return Map.of("total", total, "items", page.stream().map(entry -> {
            Map<String, Object> captured = entry.snapshot;
            Map<String, Object> evidence = new LinkedHashMap<>(captured);
            evidence.remove("sms");
            Object sms = captured.get("sms");
            if (sms instanceof Map<?, ?> details) evidence.put("smsId", details.get("smsId"));
            evidence.put("reportAccepted", Boolean.TRUE.equals(entry.reportAccepted));
            return evidence;
        }).toList());
    }

    public Map<String, Object> metrics() {
        int active = entries.size();
        int sms;
        synchronized (smsAdmission) { sms = smsIdentities.size(); }
        var result = new LinkedHashMap<String, Object>(Map.of("numbers", devices.size(), "activeAssociations", active, "retainedRecords", retainedCount(),
                "smsEvents", sms, "matched", matched.sum(), "duplicates", duplicates.sum(),
                "unmatched", unmatched.sum(), "noListeners", noListeners.sum(),
                "reportAccepted", reportAccepted.sum(), "reportFailed", reportFailed.sum()));
        result.put("leaseExecutions", leaseExecutions.sum());
        return Map.copyOf(result);
    }

    private int retainedCount() { synchronized (historyGate) { return history.size() + entries.size(); } }

    private static Map<String, Object> rejection(String id, String reason) {
        return Map.of("messageId", id, "status", "REJECTED", "reason", reason, "revision", 1);
    }
    public static String string(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String s) || s.isBlank() || s.length() > 256)
            throw new IllegalArgumentException("Invalid " + key);
        return s;
    }
    public static long number(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Number n) || n.doubleValue() != n.longValue())
            throw new IllegalArgumentException("Invalid " + key);
        return n.longValue();
    }
    private static List<Template> templates(Map<String, Object> request) {
        if (!(request.get("templates") instanceof List<?> list) || list.isEmpty() || list.size() > 8)
            throw new IllegalArgumentException("Invalid templates");
        List<Template> templates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Invalid template");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
            int priority = Math.toIntExact(number(map, "priority"));
            String kind = string(map, "kind");
            if (priority < 0 || priority > 999 || !Set.of("CODE", "ANY").contains(kind))
                throw new IllegalArgumentException("Invalid template");
            templates.add(new Template(string(map, "id"), priority, kind.equals("ANY"),
                    kind.equals("ANY") ? "" : string(map, "prefix")));
        }
        return List.copyOf(templates);
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static final class Sim {
        final String groupId;
        final String replicaKey;
        final Supplier<Map<String, String>> properties;
        final Supplier<String> workerId;
        final Supplier<String> runtimeState;
        final BooleanSupplier desiredRunning;
        final Map<String, Entry> active = new LinkedHashMap<>();
        boolean accepting = true;
        boolean starting;
        Sim(String groupId, String replicaKey, Supplier<Map<String, String>> properties, Supplier<String> workerId,
            Supplier<String> runtimeState, BooleanSupplier desiredRunning) {
            this.groupId = groupId; this.replicaKey = replicaKey; this.desiredRunning = desiredRunning;
            this.properties = properties; this.workerId = workerId; this.runtimeState = runtimeState;
        }
    }
    private record Template(String id, int priority, boolean any, String prefix) {
        boolean matches(String text) {
            return any || text.startsWith(prefix) && text.length() == prefix.length() + 6
                    && text.substring(prefix.length()).chars().allMatch(c -> c >= '0' && c <= '9');
        }
    }
    private record Publication(Entry entry, WorkerOutcomeReporter reporter, long time, Map<String, Object> snapshot) {}
    private static final class Entry {
        final String id;
        final Map<String, Object> specification;
        final long sequence;
        final Sim sim;
        final List<Template> templates;
        final long startedAt;
        final long leaseUntil;
        volatile Map<String, Object> snapshot;
        volatile Boolean reportAccepted;
        WorkerOutcomeReporter reporter;
        long revision, reportTime;
        boolean queued;
        Entry(String id, String app, Map<String, Object> specification, long sequence, Sim sim, List<Template> templates,
              long startedAt, long leaseUntil, String workerId, WorkerOutcomeReporter reporter, Map<String, String> properties) {
            this.id = id; this.specification = specification; this.sequence = sequence; this.sim = sim;
            this.templates = templates; this.startedAt = startedAt; this.leaseUntil = leaseUntil; this.reporter = reporter;
            this.snapshot = Map.of("messageId", id, "applicationId", app, "country", properties.get("country"),
                    "phoneNumber", properties.get("phone"), "workerId", workerId, "startedAt", startedAt, "leaseUntil", leaseUntil,
                    "status", "WAITING", "revision", 0);
        }
    }
}
