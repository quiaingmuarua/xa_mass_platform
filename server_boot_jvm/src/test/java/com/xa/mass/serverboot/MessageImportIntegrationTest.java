package com.xa.mass.serverboot;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Tag("scenario-composition")
class MessageImportIntegrationTest {
    static final String PATH = "/api/v1/messages/tasks";
    static Map<String, Object> input(String id) { return Map.of("appId", "demo", "requestId", id, "name", "import-proof", "recipientCountry", "CN", "body", "{}"); }
    @Test @Timeout(120)
    @SuppressWarnings("unchecked")
    void applicationsAreFrozenAcrossRestartAndOpaqueTextIsPreservedWithoutLabInterpretation() throws Exception {
        try (var fixture = new MessagesAssemblyIntegrationTest.Fixture()) {
            var settings = applicationSettings(false);
            fixture.start(true, settings);
            var catalog = Jsons.parseObject(fixture.get("/api/v1/messages/catalog").body());
            assertThat(catalog).containsEntry("version", "0.3.0-preview");
            assertThat((List<Map<String, Object>>) catalog.get("applications")).extracting(row -> row.get("id"))
                    .containsExactly("demo", "app-a", "app-b");
            String body = "  你好 {{name}}\n原样内容  ";
            var tasks = new LinkedHashMap<String, String>();
            for (String app : List.of("demo", "app-a", "app-b")) {
                var request = new HashMap<>(input(app)); request.put("appId", app); request.put("body", body);
                var response = fixture.post(PATH, request); assertThat(response.statusCode()).isEqualTo(201);
                String taskId = (String) Jsons.parseObject(response.body()).get("taskId"); tasks.put(app, taskId);
                assertThat(task(fixture, taskId)).containsEntry("appId", app).containsEntry("workerGroupId", app + "-sim")
                        .containsEntry("body", body).containsEntry("inputVersion", "3").containsEntry("sendTotal", 0L);
                var conflict = new HashMap<>(request); conflict.put("appId", app.equals("demo") ? "app-a" : "demo");
                assertThat(fixture.post(PATH, conflict).statusCode()).isEqualTo(409);
            }
            fixture.restart(true, settings);
            for (var entry : tasks.entrySet()) {
                var request = new HashMap<>(input(entry.getKey())); request.put("appId", entry.getKey()); request.put("body", body);
                assertThat(Jsons.parseObject(fixture.post(PATH, request).body())).containsEntry("taskId", entry.getValue());
            }
            fixture.restart(true, applicationSettings(true));
            String taskId = tasks.get("app-a");
            assertThat(fixture.upload(taskId, "86123").statusCode()).isEqualTo(200);
            var item = fixture.context.getBean(TaskRuntime.class).loadTaskItems(taskId, List.of(messageId(taskId, "+86123"))).get(messageId(taskId, "+86123"));
            assertThat(item.payload()).containsEntry("body", body);
            assertThat(item.workerSelector().executorName()).isEqualTo("worker.messaging.available");
            assertThat(task(fixture, taskId)).containsEntry("workerGroupId", "app-a-sim").containsEntry("appId", "app-a");
            assertThat(fixture.post(PATH + "/" + taskId + "/approve", 1).statusCode()).isEqualTo(200);
            assertThat(fixture.post(PATH + "/" + taskId + "/close", Map.of()).statusCode()).isEqualTo(200);
            for (String field : List.of("senderPhone", "workerGroupId", "recipientIds")) {
                var rejected = new HashMap<>(input("invalid")); rejected.put(field, "unexpected");
                assertThat(fixture.post(PATH, rejected).statusCode()).isEqualTo(400);
            }
            var absent = new HashMap<>(input("absent")); absent.remove("appId");
            assertThat(fixture.post(PATH, absent).statusCode()).isEqualTo(400);
        }
    }

    private static Map<String, String> applicationSettings(boolean swap) {
        var settings = new LinkedHashMap<String, String>();
        var groups = new LinkedHashMap<String, Object>();
        var apps = List.of("demo", "app-a", "app-b");
        for (int index = 0; index < apps.size(); index++) {
            String app = apps.get(index), group = app + "-sim";
            String bound = swap && index > 0 ? apps.get(3 - index) + "-sim" : group;
            String prefix = "xa.mass.scenarios.messages.applications[" + index + "].";
            settings.put(prefix + "id", app); settings.put(prefix + "label", app); settings.put(prefix + "worker-group-id", bound);
            groups.put(group, Map.of("attributes", Map.of(), "eventCodes", List.of("extension.worker.message.send")));
            settings.put("xa.mass.worker-matching.groups." + group + ".pools[0]", "messaging");
            settings.put("xa.mass.worker-matching.groups." + group + ".functions[0]", "worker.messaging.available");
        }
        settings.put("xa.mass.worker-assembly.group-config-json", Jsons.toJson(groups));
        return settings;
    }
    @Test @Timeout(240)
    void largeImportRestartAndCrossFileDedupUseRealOwnersWithoutRunningWorkers() throws Exception {
        try (var fixture = new MessagesAssemblyIntegrationTest.Fixture()) {
            fixture.start(true, Map.of());
            var create = fixture.post(PATH, input("bulk")); assertThat(create.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(create.body()).get("taskId");
            String target = PATH + "/" + id;
            assertThat(task(fixture, id)).containsEntry("state", "pre_review").containsEntry("sendTotal", 0L);
            assertThat(fixture.post(target + "/approve", 0).statusCode()).isEqualTo(409);
            String numbers = IntStream.range(0, 100000).mapToObj(i -> "86138" + (10000000 + i)).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(fixture.upload(id, numbers + "\nbad").statusCode()).isEqualTo(400);
            assertThat(task(fixture, id)).containsEntry("sendTotal", 0L);
            var imported = fixture.upload(id, numbers); assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
            assertThat(Jsons.parseObject(imported.body())).containsEntry("confirmedAddedCount", 100000L);
            String message = messageId(id, "+8613810000000");
            var owner = fixture.context.getBean(TaskRuntime.class);
            var original = owner.loadTaskItems(id, List.of(message)).get(message);
            assertThat(original.expireAtMillis() - original.createdAtMillis()).isEqualTo(Duration.ofDays(365).toMillis());
            var repeated = fixture.upload(id, "+8613810000000\n8613810000000\n8613999999999");
            assertThat(Jsons.parseObject(repeated.body())).containsEntry("confirmedAddedCount", 1L).containsEntry("existingCount", 1L).containsEntry("duplicateCount", 1L);
            assertThat(owner.loadTaskItems(id, List.of(message)).get(message)).isEqualTo(original);
            fixture.restart(true, Map.of());
            assertThat(Jsons.parseObject(fixture.post(PATH, input("bulk")).body())).containsEntry("taskId", id);
            var conflict = new HashMap<>(input("bulk")); conflict.put("body", "{ }");
            assertThat(fixture.post(PATH, conflict).statusCode()).isEqualTo(409);
            assertThat(fixture.context.getBean(TaskRuntime.class).loadTaskItems(id, List.of(message)).get(message)).isEqualTo(original);
            assertThat(fixture.post(target + "/approve", 100000).statusCode()).isEqualTo(409);
            assertThat(fixture.post(target + "/approve", 100001).statusCode()).isEqualTo(200);
            assertThat(fixture.post(target + "/close", Map.of()).statusCode()).isEqualTo(200);
            assertThat(task(fixture, id)).containsEntry("state", "terminal").containsEntry("sendTotal", 100001L).containsEntry("sentCount", 0L);
            assertThat(fixture.post(target + "/approve", 100001).statusCode()).isEqualTo(409);
            assertThat(fixture.upload(id, "8613810000000").statusCode()).isEqualTo(409);
            String other = (String) Jsons.parseObject(fixture.post(PATH, input("other")).body()).get("taskId");
            assertThat(Jsons.parseObject(fixture.upload(other, "8613810000000").body())).containsEntry("confirmedAddedCount", 1L);
            assertThat(messageId(other, "+8613810000000")).isNotEqualTo(message);
            assertThat(fixture.context.getBean(TaskRuntime.class).loadTaskItems(other, List.of(messageId(other, "+8613810000000"))))
                    .containsKey(messageId(other, "+8613810000000"));
        }
    }
    @Test @Timeout(60)
    void legacyTasksRemainReadableAndClosableWhileManagedAndForeignBusinessTasksAreProtected() throws Exception {
        try (var fixture = new MessagesAssemblyIntegrationTest.Fixture()) {
            fixture.start(true, Map.of());
            var creation = fixture.context.getBean(TaskCreationService.class);
            String legacy = creation.create(new TaskCreateRequest("messages", "demo-sim", 50, 3, List.of(), "legacy",
                    Map.of("scenario", "messages", "recipientCountry", "CN", "body", "{}"))).taskId();
            assertThat(task(fixture, legacy)).containsEntry("name", "legacy").doesNotContainKey("inputVersion");
            assertThat(fixture.upload(legacy, "86123").statusCode()).isEqualTo(409);
            assertThat(fixture.post(PATH + "/" + legacy + "/approve", 1).statusCode()).isEqualTo(409);
            assertThat(fixture.post(PATH + "/" + legacy + "/close", Map.of()).statusCode()).isEqualTo(200);
            assertThat(fixture.post("/api/v1/tasks/" + legacy + "/results:export", Map.of()).statusCode()).isEqualTo(200);
            String managed = fixture.context.getBean(ProjectDirectory.class).requireManagedTaskId("messages", "demo-sim");
            String foreign = creation.create(new TaskCreateRequest("messages", "demo-sim", 50, 3, List.of(), "foreign", Map.of("scenario", "other"))).taskId();
            for (String task : List.of(managed, foreign)) {
                assertThat(fixture.upload(task, "86123").statusCode()).isEqualTo(400);
                assertThat(fixture.post(PATH + "/" + task + "/approve", 1).statusCode()).isEqualTo(400);
                assertThat(fixture.post(PATH + "/" + task + "/close", Map.of()).statusCode()).isEqualTo(400);
            }
            var old = new HashMap<>(input("old")); old.put("recipientIds", List.of("+86123"));
            assertThat(fixture.post(PATH, old).statusCode()).isEqualTo(400);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> task(MessagesAssemblyIntegrationTest.Fixture fixture, String id) { return (Map<String, Object>) fixture.detail(id).get("task"); }
    private static String messageId(String task, String number) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of("messages/v2/message", task, number)) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return "message-" + HexFormat.of().formatHex(digest.digest());
    }
}
