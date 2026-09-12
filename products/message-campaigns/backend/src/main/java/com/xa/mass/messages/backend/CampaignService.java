package com.xa.mass.messages.backend;

import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.context.SmartLifecycle;

/** Run-local business records; all scheduling and Result reads use Server application services. */
public final class CampaignService implements SmartLifecycle, AutoCloseable {
    public static final List<String> COUNTRIES = List.of("CN", "US", "GB");
    public static final int MAX_CAMPAIGNS = 50, MAX_MESSAGES = 50_000;
    private static final Map<String, Integer> STAGES = Map.of("SENT", 6, "DELIVERED", 7, "READ", 8, "REPLIED", 9);
    private final WorkerGroupRegistrationService registrations;
    private final TaskCreationService creation;
    private final TaskDataService data;
    private final TaskLifecycleService lifecycle;
    private final String workerGroupId;
    private final List<String> events;
    private final Object gate = new Object();
    private final Map<String, Campaign> campaigns = new LinkedHashMap<>();
    private final Map<String, Campaign> requests = new HashMap<>();
    private final String runId = UUID.randomUUID().toString();
    private final LongAdder submissionUnknown = new LongAdder(), observationErrors = new LongAdder();
    private ThreadPoolExecutor submitter;
    private ScheduledExecutorService observer;
    private volatile boolean running;
    private boolean closed;
    private int messageCount, nextCampaign;

    public CampaignService(WorkerGroupRegistrationService registrations, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle, String workerGroupId,
            List<String> events) {
        if (workerGroupId == null || workerGroupId.isBlank())
            throw new IllegalArgumentException("Expected WorkerGroup");
        this.registrations = registrations; this.creation = creation; this.data = data; this.lifecycle = lifecycle;
        this.workerGroupId = workerGroupId; this.events = List.copyOf(events);
    }

    @Override public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("Messages run closed");
        try {
            registrations.register(workerGroupId, Map.of(), events);
            submitter = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
            observer = Executors.newSingleThreadScheduledExecutor();
            running = true;
            observer.scheduleWithFixedDelay(this::observe, 100, 100, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) { stop(); throw error; }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }
    @Override public boolean isAutoStartup() { return true; }
    private void requireRunning() { if (!running) throw new ProductError(503, "Messages run unavailable"); }

    public Map<String, Object> catalog() {
        requireRunning();
        return Map.of("runId", runId, "version", "0.1.0-preview", "countries", COUNTRIES.stream()
                .map(c -> Map.of("id", c, "workerGroupId", workerGroupId)).toList(),
                "limits", Map.of("campaigns", MAX_CAMPAIGNS, "messages", MAX_MESSAGES, "recipientsPerCampaign", 1000));
    }

    public Map<String, Object> create(Map<String, Object> input) {
        Specification specification = Specification.parse(input);
        synchronized (gate) {
            requireRunning();
            Campaign previous = requests.get(specification.requestId());
            if (previous != null) {
                if (!previous.specification.equals(specification)) throw new ProductError(409, "requestId has different content");
                return previous.view();
            }
            if (campaigns.size() == MAX_CAMPAIGNS || messageCount + specification.recipientIds().size() > MAX_MESSAGES)
                throw new ProductError(429, "Messages run capacity exhausted");
            Campaign campaign = new Campaign(specification, workerGroupId);
            campaigns.put(campaign.id, campaign); requests.put(specification.requestId(), campaign);
            messageCount += campaign.messages.size();
            try { submitter.execute(() -> submit(campaign)); }
            catch (RejectedExecutionException full) {
                campaigns.remove(campaign.id); requests.remove(specification.requestId());
                messageCount -= campaign.messages.size();
                throw new ProductError(429, "Campaign submission queue full");
            }
            return campaign.view();
        }
    }

    void submit(Campaign campaign) {
        try {
            requireRunning();
            Map<String,Object> selector=new LinkedHashMap<>();
            selector.put("worker.country",Map.of("op","eq","values",List.of(campaign.specification.country())));
            if (campaign.specification.senderPhone()!=null) selector.put("worker.phone",Map.of("op","eq","values",List.of(campaign.specification.senderPhone())));
            campaign.taskId=creation.create(new TaskCreateRequest(campaign.group,"worker.messaging.available",50,3)).taskId();
            for (int start = 0; start < campaign.messages.size(); start += 100) {
                requireRunning();
                var items = campaign.messages.subList(start, Math.min(start + 100, campaign.messages.size())).stream()
                        .map(message -> new TaskItemRequest(message.id, "extension.worker.message.send", Map.of(
                                "campaignId", campaign.id, "messageId", message.id, "country", campaign.specification.country(),
                                "recipientId", message.recipient, "body", campaign.specification.body()), 5, 60_000L, selector)).toList();
                var appended = data.appendFiniteTaskItems(campaign.taskId, items);
                if (appended.size() != items.size() || items.stream().anyMatch(item ->
                        appended.get(item.messageId()) == null || !"applied".equals(appended.get(item.messageId()).status().wireValue())))
                    throw new IllegalStateException("Campaign append not fully confirmed");
            }
            requireRunning();
            lifecycle.approve(campaign.taskId);
            campaign.submission = "SUBMITTED";
        } catch (RuntimeException error) {
            campaign.submission = "SUBMISSION_UNCONFIRMED";
            submissionUnknown.increment();
        }
    }

    void observe() {
        if (!running) return;
        List<Campaign> all;
        synchronized (gate) { all = List.copyOf(campaigns.values()); }
        if (all.isEmpty()) return;
        // A single Task-scoped bounded query per turn, including already replied messages.
        Campaign campaign = all.get(Math.floorMod(nextCampaign++, all.size()));
        if (campaign.taskId == null) return;
        try {
            var observed = data.loadTaskItemResults(campaign.taskId, campaign.messages.stream().map(m -> m.id).toList());
            for (Message message : campaign.messages) accept(campaign, message, observed.get(message.id));
        } catch (RuntimeException error) { observationErrors.increment(); }
    }

    void accept(Campaign campaign, Message message, TaskItemResultResponse result) {
        if (result == null || result.status() == TaskItemResultStatus.NOT_OBSERVED) return;
        synchronized (message) {
            if (result.status() == TaskItemResultStatus.FAILED) {
                if (message.snapshot.isEmpty()) message.failedObserved = true;
                return;
            }
            Map<String, Object> snapshot;
            try { snapshot = Jsons.parseObject(result.opaqueResultPayload()); }
            catch (RuntimeException invalid) { observationErrors.increment(); return; }
            Integer stage = STAGES.get(snapshot.get("status"));
            if (stage == null || !campaign.id.equals(snapshot.get("campaignId")) || !message.id.equals(snapshot.get("messageId"))
                    || !message.recipient.equals(snapshot.get("recipientId")) || !campaign.specification.country().equals(snapshot.get("country"))
                    || !campaign.specification.body().equals(snapshot.get("body"))
                    || !(snapshot.get("workerId") instanceof String worker) || worker.isBlank()
                    || !(snapshot.get("phone") instanceof String phone) || phone.isBlank()
                    || !(snapshot.get("observedAtMillis") instanceof Number time) || time.longValue() <= 0
                    || campaign.specification.senderPhone() != null && !campaign.specification.senderPhone().equals(phone)
                    || !message.snapshot.isEmpty() && !message.snapshot.get("workerId").equals(worker)
                    || stage == 9 && (!(snapshot.get("reply") instanceof String reply) || reply.isBlank()
                        || !(snapshot.get("replyRequestId") instanceof String replyId) || replyId.isBlank())) {
                observationErrors.increment(); return;
            }
            if (stage < message.stage || stage == message.stage && time.longValue() <= message.observedAt) return;
            message.stage = stage; message.observedAt = time.longValue();
            message.snapshot = Map.copyOf(snapshot); message.failedObserved = false;
            long now = System.currentTimeMillis();
            if (message.firstObserved == 0) message.firstObserved = now;
            message.lastObserved = now;
        }
    }

    public Map<String, Object> page(int offset, int limit) {
        requirePage(offset, limit);
        synchronized (gate) {
            requireRunning();
            return Map.of("runId", runId, "total", campaigns.size(), "items", campaigns.values().stream()
                    .skip(offset).limit(limit).map(Campaign::view).toList());
        }
    }
    public Map<String, Object> get(String id) { return require(id).view(); }
    public Map<String, Object> messages(String id, int offset, int limit) {
        requirePage(offset, limit); Campaign campaign = require(id);
        return Map.of("runId", runId, "total", campaign.messages.size(), "items", campaign.messages.stream()
                .skip(offset).limit(limit).map(Message::view).toList());
    }
    private Campaign require(String id) {
        synchronized (gate) {
            requireRunning(); Campaign value = campaigns.get(id);
            if (value == null) throw new ProductError(404, "Campaign not found");
            return value;
        }
    }
    public Map<String, Object> metrics() {
        List<Campaign> all;
        synchronized (gate) { requireRunning(); all = List.copyOf(campaigns.values()); }
        Map<String, Long> stages = new TreeMap<>();
        List<Long> sending = new ArrayList<>(), receipts = new ArrayList<>();
        for (Campaign campaign : all) for (Message message : campaign.messages) synchronized (message) {
            stages.merge(message.status(), 1L, Long::sum);
            if (message.firstObserved > 0) sending.add(message.firstObserved - campaign.createdAt);
            if (message.stage > 6) receipts.add(Math.max(0, message.lastObserved - message.observedAt));
        }
        return Map.of("runId", runId, "campaigns", all.size(), "messages", all.stream().mapToInt(c -> c.messages.size()).sum(),
                "statuses", stages, "submissionUnknown", submissionUnknown.sum(), "observationErrors", observationErrors.sum(),
                "submissionQueue", submitter.getQueue().size(), "sendingLatencyMillis", percentiles(sending),
                "receiptObservationLatencyMillis", percentiles(receipts));
    }
    private static Map<String, Object> percentiles(List<Long> values) {
        Collections.sort(values); int count = values.size();
        return Map.of("count", count, "p95", count == 0 ? 0 : values.get((int) Math.ceil(count * .95) - 1),
                "p99", count == 0 ? 0 : values.get((int) Math.ceil(count * .99) - 1));
    }
    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new ProductError(400, "Invalid page");
    }
    @Override public synchronized void stop() {
        if (closed) return;
        closed = true;
        synchronized (gate) { running = false; }
        ExecutorService[] owned = {submitter, observer};
        for (var executor : owned) if (executor != null) executor.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var executor : owned) if (executor != null) try {
            executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
    }
    @Override public void close() { stop(); }

    static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max)
            throw new ProductError(400, "Invalid " + key);
        return value;
    }
    record Specification(String requestId, String name, String country, String body,
            List<String> recipientIds, String senderPhone) {
        static Specification parse(Map<String, Object> input) {
            if (input == null || !Set.of("requestId", "name", "country", "body", "recipientIds", "senderPhone").containsAll(input.keySet()))
                throw new ProductError(400, "Unknown campaign fields");
            String country = text(input, "country", 2);
            if (!COUNTRIES.contains(country)) throw new ProductError(400, "Unsupported country");
            if (!(input.get("recipientIds") instanceof List<?> recipients) || recipients.isEmpty() || recipients.size() > 1000
                    || recipients.stream().anyMatch(r -> !(r instanceof String s) || s.isBlank() || s.length() > 128)
                    || new HashSet<>(recipients).size() != recipients.size())
                throw new ProductError(400, "Expected 1..1000 unique recipientIds");
            Object rawPhone = input.get("senderPhone");
            String phone = rawPhone == null || rawPhone instanceof String value && value.isBlank()
                    ? null : text(input, "senderPhone", 128);
            return new Specification(text(input, "requestId", 128), text(input, "name", 128), country,
                    text(input, "body", 4096), recipients.stream().map(String.class::cast).toList(), phone);
        }
    }
    static final class Campaign {
        final String id = UUID.randomUUID().toString();
        final long createdAt = System.currentTimeMillis();
        final Specification specification;
        final String group;
        final List<Message> messages;
        volatile String taskId, submission = "SUBMITTING";
        Campaign(Specification specification, String group) {
            this.specification = specification; this.group = group;
            this.messages = specification.recipientIds().stream().map(Message::new).toList();
        }
        Map<String, Object> view() {
            Map<String, Long> counts = new TreeMap<>();
            for (Message message : messages) synchronized (message) { counts.merge(message.status(), 1L, Long::sum); }
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id); result.put("requestId", specification.requestId()); result.put("name", specification.name());
            result.put("country", specification.country()); result.put("body", specification.body());
            result.put("senderPhone", specification.senderPhone()); result.put("createdAt", createdAt);
            result.put("workerGroupId", group); result.put("taskId", taskId); result.put("submission", submission);
            result.put("messageCount", messages.size()); result.put("statuses", counts);
            return result;
        }
    }
    static final class Message {
        final String id = UUID.randomUUID().toString(), recipient;
        Map<String, Object> snapshot = Map.of();
        int stage;
        long observedAt, firstObserved, lastObserved;
        boolean failedObserved;
        Message(String recipient) { this.recipient = recipient; }
        String status() { return snapshot.isEmpty() ? failedObserved ? "EXECUTION_FAILED" : "NOT_OBSERVED" : (String) snapshot.get("status"); }
        synchronized Map<String, Object> view() {
            var result = new LinkedHashMap<>(snapshot);
            result.put("id", id); result.put("recipientId", recipient); result.put("status", status());
            result.put("lastObservedAt", lastObserved);
            return result;
        }
    }
    public static final class ProductError extends RuntimeException {
        final int status;
        ProductError(int status, String message) { super(message); this.status = status; }
    }
}
