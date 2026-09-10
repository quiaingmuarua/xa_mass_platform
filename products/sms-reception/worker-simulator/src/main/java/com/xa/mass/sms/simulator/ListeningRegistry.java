package com.xa.mass.sms.simulator;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Business state of one finite simulator run. No Kernel state or delivery retries. */
public final class ListeningRegistry {
    public static final int MAX_LISTENERS = 50_000;
    public static final int MAX_SMS = 100_000;
    public static final int PER_NUMBER = 64;
    private final Clock clock;
    private final int listenerLimit;
    private final int smsLimit;
    private final Map<String, Sim> sims = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<Entry> ordered = new ArrayList<>();
    private final Map<String, String> smsIdentities = new HashMap<>();
    private final Object admission = new Object();
    private final Object smsAdmission = new Object();
    private final LongAdder matched = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder unmatched = new LongAdder();
    private final LongAdder noListeners = new LongAdder();
    private final LongAdder reportAccepted = new LongAdder();
    private final LongAdder reportFailed = new LongAdder();
    private volatile boolean closed;

    public ListeningRegistry() { this(Clock.systemUTC(), MAX_LISTENERS, MAX_SMS); }
    ListeningRegistry(Clock clock, int listenerLimit, int smsLimit) {
        this.clock = clock;
        this.listenerLimit = listenerLimit;
        this.smsLimit = smsLimit;
    }

    public Sim addSim(String phone, String country, Supplier<String> workerId, Supplier<String> runtimeState) {
        if (sims.containsKey(phone)) throw new IllegalArgumentException("Duplicate number");
        Sim sim = new Sim(phone, country, workerId, runtimeState);
        sims.put(phone, sim);
        return sim;
    }

    public Map<String, Object> listen(Sim sim, Map<String, Object> request, WorkerOutcomeReporter reporter) {
        String id = string(request, "listenerId");
        String application = string(request, "applicationId");
        String country = string(request, "country");
        long seconds = number(request, "listenSeconds");
        long deadline = number(request, "setupDeadline");
        if (seconds < 1 || seconds > 300 || !sim.country.equals(country))
            throw new IllegalArgumentException("Invalid listening window or country");
        List<Template> templates = templates(request);
        Map<String, Object> specification = Map.of("application", application, "country", country,
                "seconds", seconds, "deadline", deadline, "templates", templates);
        synchronized (admission) {
            Entry previous = entries.get(id);
            if (previous != null) {
                if (!previous.specification.equals(specification)) throw new IllegalArgumentException("Listener conflict");
                return previous.snapshot;
            }
            long now = clock.millis();
            if (closed || ordered.size() >= listenerLimit) return rejection(id, "Listener capacity exhausted");
            if (now >= deadline) return rejection(id, "Establishment deadline elapsed");
            String workerId = sim.workerId.get();
            if (workerId == null || workerId.isBlank()) return rejection(id, "Worker identity unavailable");
            synchronized (sim) {
                if (sim.active.size() >= PER_NUMBER) {
                    Entry refused = new Entry(id, application, specification, ordered.size(), sim,
                            templates, now, now, workerId, null);
                    refused.snapshot = rejection(id, "Number listener capacity exhausted");
                    ordered.add(refused); entries.put(id, refused);
                    return refused.snapshot;
                }
                Entry entry = new Entry(id, application, specification, ordered.size(), sim,
                        templates, now, now + seconds * 1000, workerId, reporter);
                sim.active.put(id, entry);
                ordered.add(entry);
                entries.put(id, entry);
                return entry.snapshot;
            }
        }
    }

    public Map<String, Object> cancel(Sim addressedSim, Map<String, Object> request) {
        Entry entry = entries.get(string(request, "listenerId"));
        if (entry == null) throw new IllegalArgumentException("Listener not established");
        if (entry.sim != addressedSim) throw new IllegalArgumentException("Cancellation addressed to another number");
        Publication publication;
        synchronized (entry.sim) {
            long now = clock.millis();
            publication = finish(entry, now >= entry.expiresAt ? "EXPIRED" : "CANCELLED", now, Map.of());
        }
        publish(publication);
        return entry.snapshot;
    }

    public Map<String, Object> receive(String phone, String smsId, String text) {
        long now = clock.millis();
        Sim sim = sims.get(phone);
        if (sim == null || smsId == null || smsId.isBlank() || smsId.length() > 128
                || text == null || text.isBlank() || text.length() > 1024)
            throw new IllegalArgumentException("Invalid simulated SMS");
        String digest = fingerprint(phone + "\n" + text);
        synchronized (smsAdmission) {
            if (closed) throw new IllegalStateException("Simulator closed");
            String previous = smsIdentities.get(smsId);
            if (previous != null) {
                if (!previous.equals(digest)) throw new IllegalArgumentException("SMS identity conflict");
                duplicates.increment();
                return Map.of("status", "DUPLICATE", "smsId", smsId);
            }
            if (smsIdentities.size() >= smsLimit) throw new IllegalStateException("SMS record capacity exhausted");
            smsIdentities.put(smsId, digest);
        }
        List<Publication> publications = new ArrayList<>();
        Entry winner = null;
        Template winningTemplate = null;
        synchronized (sim) {
            expire(sim, now, publications);
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
                publications.add(finish(winner, "RECEIVED", now, sms));
                matched.increment();
            }
        }
        publications.forEach(this::publish);
        return winner == null ? Map.of("status", "IGNORED", "smsId", smsId)
                : Map.of("status", "MATCHED", "smsId", smsId, "listenerId", winner.id,
                        "templateId", winningTemplate.id);
    }

    public void expire() {
        long now = clock.millis();
        for (Sim sim : sims.values()) {
            List<Publication> publications = new ArrayList<>();
            synchronized (sim) { expire(sim, now, publications); }
            publications.forEach(this::publish);
        }
    }

    private void expire(Sim sim, long now, List<Publication> publications) {
        for (Entry entry : List.copyOf(sim.active.values())) {
            if (now >= entry.expiresAt) publications.add(finish(entry, "EXPIRED", now, Map.of()));
        }
    }

    private Publication finish(Entry entry, String status, long now, Map<String, Object> detail) {
        if (!"LISTENING".equals(entry.snapshot.get("status"))) return null;
        Map<String, Object> next = new LinkedHashMap<>(entry.snapshot);
        next.put("status", status);
        next.put("revision", 1);
        next.put("endedAt", now);
        if (!detail.isEmpty()) next.put("sms", Map.copyOf(detail));
        entry.snapshot = Map.copyOf(next);
        entry.sim.active.remove(entry.id);
        WorkerOutcomeReporter reporter = entry.reporter;
        entry.reporter = null;
        return new Publication(entry, reporter, now, entry.snapshot);
    }

    private void publish(Publication publication) {
        if (publication == null) return;
        boolean accepted = false;
        try {
            accepted = publication.reporter != null && publication.reporter.report(9, publication.time,
                    Jsons.toJson(publication.snapshot));
        } catch (RuntimeException ignored) {
            // Business state is already final. A failed send never selects another listener.
        }
        publication.entry.reportAccepted = accepted;
        if (accepted) reportAccepted.increment(); else reportFailed.increment();
    }

    public void close() {
        synchronized (admission) { closed = true; }
        for (Sim sim : sims.values()) synchronized (sim) {
            for (Entry entry : List.copyOf(sim.active.values())) finish(entry, "INTERRUPTED", clock.millis(), Map.of());
        }
    }

    public List<String> phones() { return List.copyOf(sims.keySet()); }

    public Map<String, Object> inventory(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
        List<Map<String, Object>> page = sims.values().stream().skip(offset).limit(limit).map(sim -> {
            String workerId = Objects.toString(sim.workerId.get(), "");
            String state = sim.runtimeState.get();
            int active;
            synchronized (sim) { active = sim.active.size(); }
            return Map.<String, Object>of("phone", sim.phone, "country", sim.country,
                    "workerId", workerId, "runtimeState", state, "activeListeners", active);
        }).toList();
        return Map.of("total", sims.size(), "offset", offset, "limit", limit, "items", page);
    }

    public Map<String, Object> records(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
        List<Entry> page;
        int total;
        synchronized (admission) {
            total = ordered.size();
            page = List.copyOf(ordered.subList(Math.min(offset, total), Math.min(total, offset + limit)));
        }
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
        int active = 0;
        for (Sim sim : sims.values()) synchronized (sim) { active += sim.active.size(); }
        int sms;
        synchronized (smsAdmission) { sms = smsIdentities.size(); }
        return Map.of("numbers", sims.size(), "listeners", entries.size(), "activeListeners", active,
                "smsEvents", sms, "matched", matched.sum(), "duplicates", duplicates.sum(),
                "unmatched", unmatched.sum(), "noListeners", noListeners.sum(),
                "reportAccepted", reportAccepted.sum(), "reportFailed", reportFailed.sum());
    }

    private static Map<String, Object> rejection(String id, String reason) {
        return Map.of("listenerId", id, "status", "REJECTED", "reason", reason, "revision", 1);
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
        final String phone;
        final String country;
        final Supplier<String> workerId;
        final Supplier<String> runtimeState;
        final Map<String, Entry> active = new LinkedHashMap<>();
        Sim(String phone, String country, Supplier<String> workerId, Supplier<String> runtimeState) {
            this.phone = phone; this.country = country; this.workerId = workerId; this.runtimeState = runtimeState;
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
        final long expiresAt;
        volatile Map<String, Object> snapshot;
        volatile Boolean reportAccepted;
        WorkerOutcomeReporter reporter;
        Entry(String id, String app, Map<String, Object> specification, long sequence, Sim sim, List<Template> templates,
              long startedAt, long expiresAt, String workerId, WorkerOutcomeReporter reporter) {
            this.id = id; this.specification = specification; this.sequence = sequence; this.sim = sim;
            this.templates = templates; this.startedAt = startedAt; this.expiresAt = expiresAt; this.reporter = reporter;
            this.snapshot = Map.of("listenerId", id, "applicationId", app, "country", sim.country,
                    "phone", sim.phone, "workerId", workerId, "startedAt", startedAt, "expiresAt", expiresAt,
                    "status", "LISTENING", "revision", 0);
        }
    }
}
