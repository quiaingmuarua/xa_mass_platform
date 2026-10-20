package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Messages finite Pool path and original-run Android callbacks, without Simulator or implementation imports. */
public final class AndroidWorkerMessagesMain {
    private static final String GROUP = AndroidWorkerProofConstants.WORKER_GROUP_ID;
    private static final String EVENT = "extension.worker.message.send";
    private static final String OBSERVATION = "extension.worker.android-demo.host.messages.read";
    private static final String BODY = "  Android local message\n{} 中文  ";
    private final AndroidWorkerProofOptions options;
    private final ProofEvidence evidence;
    private final JsonHttpClient http, local;
    private final AndroidDeviceHostClient device;
    private final AndroidRuntimeApiClient runtime;
    private String worker;

    private AndroidWorkerMessagesMain(AndroidWorkerProofOptions options) {
        this.options = options;
        evidence = new ProofEvidence(options, "messages", options.phase());
        http = new JsonHttpClient(options.serverBaseUrl(), options,
                Map.of("X-XA-Dev-User", "MTAwMA", "X-XA-Dev-Role", "admin"));
        local = new JsonHttpClient(options.deviceBaseUrl(), options, Map.of());
        device = new AndroidDeviceHostClient(local);
        runtime = new AndroidRuntimeApiClient(http);
    }

    public static void main(String[] args) throws IOException {
        var proof = new AndroidWorkerMessagesMain(AndroidWorkerProofOptions.parse(args));
        try { proof.run(); }
        catch (ProofFailure failure) { proof.evidence.failure(failure); throw failure; }
        catch (RuntimeException | Error failure) { proof.evidence.unexpectedFailure(failure); throw failure; }
        finally { proof.evidence.write(); }
    }

    private void run() throws IOException {
        for (String path : List.of("/api/v1/app-checks/catalog", "/api/v1/sms/catalog"))
            require(http.sendText("GET", path, null, "application/json", "scenario-absent").statusCode() == 404, "scenario-absent");
        var applications = rows(request("GET", "/api/v1/messages/catalog", null).get("applications"));
        require(applications.size() == 1 && "android-local".equals(applications.getFirst().get("id"))
                && GROUP.equals(applications.getFirst().get("workerGroupId")), "catalog");
        AndroidWorkerProofAssertions.awaitDeviceHealth(device, options.maximumWait());
        AndroidWorkerProofAssertions.requireDeviceEvents(device);
        require(!device.events().contains(EVENT) && device.events().contains(OBSERVATION), "local-http-read-only");
        worker = AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), null).workerId();
        evidence.workerId(worker);
        AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), worker, options.maximumWait());
        AndroidWorkerProofAssertions.awaitHot(runtime, worker, options.maximumWait());
        runtime.requirePropertiesRelation(options.endpointManagerId(), worker,
                Map.of("messaging.enabled", "true", "country", "CN", "phone", "+8617000000000"));

        if (options.phase().equals("initial")) {
            String task = create(3, true, "interactive");
            List<String> ids = ids(task, 3);
            verifyComplete(task, ids, true);
            require(rows(business(task).get("results")).stream().allMatch(row -> "SENT".equals(row.get("status"))), "sent-without-interaction");
            remember(task, ids);
            evidence.check("completedWithoutReceipts", true);
            return;
        }
        var baseline = Jsons.parseObject(Files.readString(options.baselineFile(true)));
        var checks = object(baseline.get("checks"));
        String task = (String) checks.get("taskId");
        List<String> ids = strings(checks.get("messageIds"));
        require(worker.equals(baseline.get("workerId")), "retained-worker-identity");
        switch (options.phase()) {
            case "open" -> {
                marker("ANDROID_MESSAGES_OPEN_READY");
                awaitStatus(task, ids.get(0), "READ", null);
                require(status(task, ids.get(1)).equals("SENT") && status(task, ids.get(2)).equals("SENT"), "unopened-messages");
            }
            case "reply" -> {
                marker("ANDROID_MESSAGES_REPLY_READY");
                awaitStatus(task, ids.get(0), "REPLIED", "app-reply");
                require(status(task, ids.get(1)).equals("SENT"), "other-message-unchanged");
            }
            case "notification" -> {
                String notificationTask = create(1, false, "notification");
                verifyComplete(notificationTask, ids(notificationTask, 1), false);
                marker("ANDROID_MESSAGES_NOTIFICATION_READY");
                awaitStatus(notificationTask, ids(notificationTask, 1).getFirst(), "REPLIED", "notification-reply");
                awaitStatus(task, ids.get(0), "REPLIED", "app-reply");
                require(status(task, ids.get(1)).equals("SENT") && status(task, ids.get(2)).equals("SENT"), "notification-association");
                evidence.check("notificationTaskId", notificationTask);
            }
            case "stop-start" -> {
                device.stop();
                AndroidWorkerProofAssertions.awaitStopped(device, options.maximumWait(), worker);
                require(rows(observe(ids).get("accepted")).stream().allMatch(row -> Boolean.FALSE.equals(row.get("receiptAvailable"))),
                        "reporters-revoked");
                device.start();
                AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), worker);
                AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), worker, options.maximumWait());
                AndroidWorkerProofAssertions.awaitHot(runtime, worker, options.maximumWait());
                require(rows(observe(ids).get("accepted")).size() == 3
                        && rows(observe(ids).get("accepted")).stream().allMatch(row -> Boolean.FALSE.equals(row.get("receiptAvailable"))),
                        "restart-does-not-rebind");
                awaitStatus(task, ids.get(0), "REPLIED", "app-reply");
                String next = create(1, false, "after-stop");
                verifyComplete(next, ids(next, 1), false);
                evidence.check("newTaskId", next);
            }
            case "bulk" -> {
                task = create(123, false, "bulk");
                ids = ids(task, 123);
                verifyComplete(task, ids, false);
                var preview = business(task);
                require(rows(preview.get("results")).size() == 100 && Boolean.TRUE.equals(preview.get("truncated")), "bounded-preview");
                var exported = http.sendText("POST", task(task) + "/results:export", null, "application/json", "messages-export");
                require(exported.statusCode() == 200, "export-status");
                var exportedIds = new HashSet<String>();
                exported.body().lines().filter(line -> !line.isBlank()).forEach(line -> {
                    var row = Jsons.parseObject(line);
                    require(exportedIds.add((String) row.get("messageId")), "export-duplicate");
                });
                require(exportedIds.equals(Set.copyOf(ids)), "complete-export-identities");
                evidence.check("jsonlRows", exportedIds.size());
                evidence.check("previewRows", 100);
            }
            case "restart" -> {
                require(number(taskView(task).get("createdAtMillis")) == number(checks.get("createdAtMillis")), "retained-creation-time");
                verifyResults(task, ids);
                for (List<String> batch : batches(ids))
                    require(rows(observe(batch).get("accepted")).isEmpty()
                            && strings(observe(batch).get("missing")).equals(batch), "process-local-state-lost");
                require(rows(business(task).get("results")).size() == 100, "retained-business-results");
                evidence.check("serverResultsRetained", true);
            }
            default -> throw new IllegalArgumentException("Unknown Messages proof phase");
        }
        remember(task, ids);
    }

    private String create(int count, boolean country, String suffix) {
        var input = new LinkedHashMap<String, Object>();
        input.put("requestId", options.proofId() + "-" + suffix);
        input.put("appId", "android-local");
        input.put("name", "Android local " + suffix);
        input.put("recipientCountry", "CN");
        if (country) input.put("senderCountry", "CN");
        input.put("body", BODY);
        String id = (String) request("POST", "/api/v1/messages/tasks", input).get("taskId");
        require(id.equals(request("POST", "/api/v1/messages/tasks", input).get("taskId")), "create-reuse");
        String content = String.join("\n", recipients(count));
        var first = http.sendText("POST", "/api/v1/messages/tasks/" + id + "/recipients:import", content, "text/plain", "import");
        require(first.statusCode() == 200 && number(Jsons.parseObject(first.body()).get("confirmedAddedCount")) == count, "import-count");
        var repeated = http.sendText("POST", "/api/v1/messages/tasks/" + id + "/recipients:import", content, "text/plain", "reimport");
        require(repeated.statusCode() == 200 && number(Jsons.parseObject(repeated.body()).get("confirmedAddedCount")) == 0
                && number(Jsons.parseObject(repeated.body()).get("existingCount")) == count, "duplicate-import");
        var descriptor = object(taskView(id).get("task"));
        require(GROUP.equals(descriptor.get("workerGroupId")) && "CLOSE_WHEN_IDLE".equals(descriptor.get("idleDisposition")), "finite-group");
        var refill = rows(descriptor.get("refill"));
        require(refill.size() == 1 && "messaging".equals(refill.getFirst().get("poolName")), "messaging-supply");
        request("POST", task(id) + "/approve", Map.of("expectedItemCount", count));
        ProofWait.until(options.maximumWait(), () -> taskView(id).get("scoreBand"),
                "terminal"::equals, "messages.terminal", "Messages Task did not finish", id);
        return id;
    }

    private void verifyComplete(String task, List<String> ids, boolean country) {
        verifyResults(task, ids);
        for (List<String> batch : batches(ids)) {
            var accepted = observe(batch);
            require(strings(accepted.get("missing")).isEmpty(), "acceptance-missing");
            var records = rows(accepted.get("accepted"));
            require(records.size() == batch.size(), "acceptance-size");
            for (var record : records) {
                String id = (String) record.get("messageId");
                int index = ids.indexOf(id);
                require(index >= 0 && worker.equals(record.get("workerId")) && task.equals(record.get("campaignId")), "acceptance-identity");
                require(hash(List.of(task, id, "CN", recipients(ids.size()).get(index), BODY)).equals(record.get("requestFingerprint")),
                        "independent-acceptance-fingerprint");
                require(!record.containsKey("body") && !record.containsKey("recipientId"), "safe-evidence");
            }
        }
        var preview = rows(request("GET", task(task) + "/results:preview", null).get("results"));
        require(preview.size() == Math.min(ids.size(), 100), "raw-preview");
        for (var row : preview) {
            var item = object(row.get("item"));
            require(EVENT.equals(item.get("eventCode")), "installed-event");
            require(object(item.get("workerSelector")).equals(Map.of("executorName", "worker.messaging.available",
                    "input", country ? Map.of("country", List.of("CN")) : Map.of())), "actual-pool-query");
        }
    }

    private void verifyResults(String task, List<String> ids) {
        List<String> recipients = recipients(ids.size());
        for (List<String> batch : batches(ids)) {
            var results = ProofWait.until(options.maximumWait(), () -> request("POST", task(task) + "/results:load", batch),
                    value -> value.keySet().equals(Set.copyOf(batch)) && value.values().stream()
                            .allMatch(row -> "succeeded".equals(object(row).get("status"))),
                    "messages.results", "Finite Results did not converge", task);
            var states = request("POST", task(task) + "/items:states", batch);
            for (String id : batch) {
                var snapshot = Jsons.parseObject((String) object(results.get(id)).get("opaqueResultPayload"));
                require(task.equals(snapshot.get("campaignId")) && id.equals(snapshot.get("messageId"))
                        && recipients.get(ids.indexOf(id)).equals(snapshot.get("recipientId"))
                        && BODY.equals(snapshot.get("body")) && "CN".equals(snapshot.get("country"))
                        && worker.equals(snapshot.get("workerId")) && "SENT".equals(snapshot.get("status"))
                        && number(snapshot.get("observedAtMillis")) > 0, "raw-sent-snapshot");
                require("+8617000000000".equals(snapshot.get("phone")), "demo-sender");
                var state = object(states.get(id));
                require("terminal".equals(state.get("band")) && "succeeded".equals(state.get("outcomeName")), "terminal-item");
            }
        }
    }

    private void awaitStatus(String task, String id, String status, String reply) {
        ProofWait.until(options.maximumWait(), () -> rows(business(task).get("results")).stream()
                        .filter(row -> id.equals(row.get("messageId"))).findFirst().orElse(Map.of()),
                row -> status.equals(row.get("status")) && (reply == null || reply.equals(row.get("reply"))),
                "messages.interaction", "Android interaction was not observed", id);
        require("terminal".equals(taskView(task).get("scoreBand")), "task-stays-terminal");
    }
    private String status(String task, String id) {
        return rows(business(task).get("results")).stream().filter(row -> id.equals(row.get("messageId")))
                .map(row -> (String) row.get("status")).findFirst().orElse("");
    }
    private Map<String, Object> observe(List<String> ids) {
        var response = local.send("POST", "/events/" + OBSERVATION + ":call", Map.of("messageIds", ids), "messages-local-evidence");
        require(response.statusCode() == 200 && "succeeded".equals(response.body().get("status")), "local-observation");
        return object(response.body().get("result"));
    }
    private void remember(String task, List<String> ids) {
        evidence.check("taskId", task);
        evidence.check("messageIds", ids);
        evidence.check("createdAtMillis", taskView(task).get("createdAtMillis"));
        evidence.check("itemCount", ids.size());
    }
    private Map<String, Object> taskView(String id) { return request("GET", "/api/v1/projects/messages/tasks/" + id, null); }
    private Map<String, Object> business(String id) { return request("GET", "/api/v1/messages/tasks/" + id + "/results", null); }
    private Map<String, Object> request(String method, String path, Object input) {
        var response = http.send(method, path, input, "messages");
        require(response.statusCode() >= 200 && response.statusCode() < 300, "http-" + response.statusCode());
        return response.body();
    }
    private static List<String> recipients(int count) {
        var result = new ArrayList<String>();
        for (int i = 0; i < count; i++) result.add("+86138" + String.format(Locale.ROOT, "%08d", i));
        return result;
    }
    private static List<String> ids(String task, int count) {
        return recipients(count).stream().map(recipient -> "message-" + hash(List.of("messages/v2/message", task, recipient))).toList();
    }
    private static String hash(List<String> fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static List<List<String>> batches(List<String> ids) {
        var batches = new ArrayList<List<String>>();
        for (int i = 0; i < ids.size(); i += 100) batches.add(ids.subList(i, Math.min(i + 100, ids.size())));
        return batches;
    }
    private static void marker(String value) { System.out.println(value); System.out.flush(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }
    @SuppressWarnings("unchecked") private static List<String> strings(Object value) { return (List<String>) value; }
    private static long number(Object value) {
        require(value instanceof Number, "numeric-response-contract");
        return ((Number) value).longValue();
    }
    private static String task(String id) { return "/api/v1/tasks/" + id; }
    private static void require(boolean condition, String name) {
        if (!condition) throw new ProofFailure("messages." + name, "Android Messages assertion failed: " + name);
    }
}
