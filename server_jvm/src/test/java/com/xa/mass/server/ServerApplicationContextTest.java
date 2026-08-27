package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.ResourceCommandController;
import com.xa.mass.server.api.v1.TaskControlController;
import com.xa.mass.server.api.v1.TaskDataController;
import com.xa.mass.server.api.v1.runtimeview.RuntimeViewController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterBatchDeliveryController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterDirectCallController;
import com.xa.mass.server.api.v1.workerdelivery.WorkerPointDeliveryController;
import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.redis.RedisWorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.server.kernelbinding.KernelOwnerAssemblyConfiguration;
import com.xa.mass.server.kernelpacer.KernelPacerAssembly;
import com.xa.mass.server.kernelpacer.KernelPacerProperties;
import com.xa.mass.server.openapi.OpenApiSnapshotSupport;
import com.xa.mass.server.directcall.DirectCallService;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallRegistrationService;
import com.xa.mass.server.workerdelivery.WorkerDeliveryOwnerAssemblyConfiguration;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.workerassembly
        .ServerConfiguredRuntimeLifecycleHost;
import com.xa.mass.server.workerdelivery.adapter
        .ServerWorkerDeliveryAdapterProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ServerApplicationContextTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> HTTP_METHODS = Set.of(
            "get",
            "put",
            "post",
            "delete",
            "patch",
            "options",
            "head",
            "trace"
    );

    @Autowired
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Test
    void assemblesTheRuntimeApiWithoutStartingDisabledJavaPacers()
            throws Exception {
        assertThat(applicationContext.getBean(TaskRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskResourceCatalog.class))
                .isNotNull();
        assertThat(applicationContext.getBean(WorkerRuntime.class))
                .isInstanceOf(RedisWorkerRuntime.class);
        assertThat(applicationContext.getBean(WorkerResourceCatalog.class))
                .isInstanceOf(RedisWorkerResourceCatalog.class);
        assertThat(applicationContext.getBean(WorkerCommandRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(WorkerResultRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                WorkerServiceabilityRuntime.class
        )).isInstanceOf(RedisWorkerServiceabilityRuntime.class);
        assertThat(applicationContext.getBean(KernelPacerRuntime.class))
                .isNotNull();
        assertThat(applicationContext.getBean(KernelPacerProperties.class)
                .preset()).isEqualTo(
                        KernelPacerRuntime.PolicyPreset.DEFAULT
                );
        assertThat(applicationContext.containsBean(
                "workerServiceabilityResultApplication"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "workerServiceabilityDispatchApplication"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "kernelPacerPolicyConfig"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "resultRoutingApplication"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "assignmentDispatchApplication"
        )).isFalse();
        assertThat(applicationContext.containsBean(
                "workerCandidateMatcher"
        )).isFalse();
        assertThat(applicationContext.getBean(
                KernelOwnerAssemblyConfiguration.class
        )).isNotNull();
        assertThat(applicationContext.getBean(KernelPacerAssembly.class)
                .snapshot().enabled()).isFalse();
        assertThat(applicationContext.getBean(
                WorkerDeliveryOwnerAssemblyConfiguration.class
        )).isNotNull();
        assertThat(applicationContext.getBean(WorkerDeliveryService.class))
                .isNotNull();
        assertThat(applicationContext.getBean(ResourceCommandController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskControlController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(TaskDataController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                WorkerGroupTaskCallRegistrationService.class
        )).isNotNull();
        assertThat(applicationContext.getBean(RuntimeViewController.class))
                .isNotNull();
        assertThat(applicationContext.getBean(RuntimeViewService.class))
                .isNotNull();
        assertThat(applicationContext.getBean(
                WorkerPointDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                AdapterBatchDeliveryController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                AdapterDirectCallController.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                DirectCallService.class
        )).isNotNull();
        assertThat(applicationContext.getBean(TaskScoreBandCore.class))
                .isInstanceOf(RedisTaskScoreBandCore.class);
        assertThat(applicationContext.getBeansOfType(
                TaskItemScoreBandCore.class
        )).hasSize(1);
        assertThat(applicationContext.getBean(WorkerScoreCore.class))
                .isInstanceOf(RedisWorkerScoreCore.class);
        assertThat(applicationContext.getBeansOfType(
                CandidateWorkerCache.class
        )).hasSize(1);
        assertThat(applicationContext.getBeansOfType(
                CandidateWarmupSchedule.class
        )).hasSize(1);
        assertThat(applicationContext.getBean(
                ServerWorkerDeliveryAdapterProperties.class
        ).instanceConfigs()).isEmpty();
        assertThat(applicationContext.getBean(
                ServerConfiguredRuntimeLifecycleHost.class
        )).isNotNull();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> liveness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/liveness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        HttpResponse<String> readiness = client.send(
                HttpRequest.newBuilder(endpoint("/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
    }

    @Test
    void rejectsRemovedWorkerResourceFields() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> groupResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group"
                        ))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"eventCodes\":[\"event\"],"
                                        + "\"itemAllocation"
                                        + "Fields\":[]}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> indexedGroupResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/indexed-legacy-group"
                        ))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"eventCodes\":[\"event\"],"
                                        + "\"indexedPropertyFields\":["
                                        + "\"worker.region\"]}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> workerResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group/"
                                        + "workers:register"
                        ))
                        .header("Content-Type", "application/json")
                        .header(
                                "X-XA-Mass-Platform-Key",
                                "local-development"
                        )
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"clientWorkerKey\":\"legacy-worker\","
                                        + "\"attributes\":{}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> bindingResponse = client.send(
                HttpRequest.newBuilder(endpoint(
                                "/api/v1/worker-groups/legacy-group/workers/"
                                        + "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                                        + ":bind"
                        ))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"clientWorkerKey\":\"legacy-worker\","
                                        + "\"transportType\":\"WEBSOCKET\","
                                        + "\"workerProperties\":{"
                                        + "\"clientWorkerKey\":"
                                        + "\"legacy-worker\"}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        HttpResponse<String> reusableTaskResponse = client.send(
                HttpRequest.newBuilder(endpoint("/api/v1/tasks"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"taskId\":\"legacy-reusable\","
                                        + "\"workerGroupId\":\"legacy-group\","
                                        + "\"profile\":\"REUSABLE_DIRECT\","
                                        + "\"allocationRule\":{},"
                                        + "\"config\":{"
                                        + "\"priority\":\"0\","
                                        + "\"maximumCandidateWorkers\":\"1\","
                                        + "\"maxRetryTimes\":\"3\"}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertThat(groupResponse.statusCode()).isEqualTo(404);
        assertThat(indexedGroupResponse.statusCode()).isEqualTo(404);
        assertThat(workerResponse.statusCode()).isEqualTo(404);
        assertThat(bindingResponse.statusCode()).isEqualTo(404);
        assertThat(reusableTaskResponse.statusCode()).isEqualTo(400);
    }

    @Test
    void exposesStableOpenApiNavigationContract() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint("/v3/api-docs"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        assertThat(response.statusCode()).isEqualTo(200);
        var document = JSON.readTree(response.body());
        assertThat(document.path("info").path("title").asText())
                .isEqualTo("XA Mass Runtime API");
        assertThat(document.path("info").path("version").asText())
                .isEqualTo("v1");
        assertThat(document.path("info").path("description").asText())
                .contains("Kernel decides scheduling")
                .contains("[Code Dictionary](/reference/error-codes)")
                .contains("[current-build JSON]"
                        + "(/reference/platform-diagnostic-codes.json)")
                .contains("`ApiErrorResponse.code` belongs to the "
                        + "`server_jvm` namespace")
                .contains("producer-local namespaces")
                .contains("does not bind API operations to diagnostic codes")
                .doesNotContain("Task Batch Lab");

        var tagNames = new ArrayList<String>();
        document.path("tags").forEach(tag ->
                tagNames.add(tag.path("name").asText()));
        assertThat(tagNames).containsExactly(
                ApiTags.WORKER_RESOURCES,
                ApiTags.TASKS,
                ApiTags.RUNTIME_VIEW,
                ApiTags.WORKER_DELIVERY
        );

        Set<String> allowedTags = Set.copyOf(tagNames);
        int operationCount = 0;
        for (var path : document.path("paths").properties()) {
            for (var candidate : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(candidate.getKey())) {
                    continue;
                }
                operationCount++;
                var operationTags = candidate.getValue().path("tags");
                assertThat(operationTags.size())
                        .as("tag count for %s %s", candidate.getKey(), path.getKey())
                        .isEqualTo(1);
                String operationTag = operationTags.get(0).asText();
                assertThat(operationTag)
                        .as("tag for %s %s", candidate.getKey(), path.getKey())
                        .isIn(allowedTags)
                        .doesNotEndWith("-controller");

                var responses = candidate.getValue().path("responses");
                var responseCodes = new ArrayList<>(responses.propertyNames());
                assertThat(responseCodes)
                        .as("responses for %s %s", candidate.getKey(), path.getKey())
                        .containsExactlyInAnyOrderElementsOf(
                                expectedResponseCodes(operationTag, path.getKey())
                        )
                        .doesNotContain("404", "409", "422");
                var successCodes = responseCodes.stream()
                        .filter(code -> code.length() == 3
                                && code.charAt(0) == '2')
                        .toList();
                assertThat(successCodes)
                        .as("success responses for %s %s",
                                candidate.getKey(), path.getKey())
                        .isNotEmpty();
                for (String successCode : successCodes) {
                    var successContent = responses.path(successCode)
                            .path("content");
                    if ("204".equals(successCode)) {
                        assertThat(successContent.isMissingNode()
                                || successContent.size() == 0)
                                .as("bodyless 204 response for %s %s",
                                        candidate.getKey(), path.getKey())
                                .isTrue();
                    } else {
                        assertThat(successContent.size())
                                .as("success content for %s %s %s",
                                        candidate.getKey(), path.getKey(),
                                        successCode)
                                .isPositive();
                        for (var mediaType : successContent.properties()) {
                            var schema = mediaType.getValue().path("schema");
                            assertThat(schema.isMissingNode()
                                    || schema.isNull()
                                    || schema.size() == 0)
                                    .as("success schema for %s %s %s %s",
                                            candidate.getKey(), path.getKey(),
                                            successCode, mediaType.getKey())
                                    .isFalse();
                            assertThat(schema.toString())
                                    .as("success schema for %s %s %s %s",
                                            candidate.getKey(), path.getKey(),
                                            successCode, mediaType.getKey())
                                    .doesNotContain("ApiErrorResponse");
                        }
                    }
                }
                for (String errorCode : Set.of("400", "429", "503")) {
                    if (responses.has(errorCode)) {
                        assertThat(responses.path(errorCode)
                                .path("content")
                                .toString())
                                .as("error schema for %s %s %s",
                                        candidate.getKey(), path.getKey(), errorCode)
                                .contains("ApiErrorResponse");
                    }
                }
            }
        }
        assertThat(operationCount).isPositive();
        assertThat(document.path("paths")
                .path("/api/v1/tasks")
                .path("post")
                .path("tags")
                .get(0)
                .asText()).isEqualTo(ApiTags.TASKS);
        assertThat(document.path("paths")
                .path("/api/v1/tasks")
                .path("post")
                .path("summary")
                .asText()).isEqualTo("Create a finite Task");
        var callOperation = document.path("paths")
                .path("/api/v1/tasks/{taskId}/items:call")
                .path("post");
        assertThat(callOperation.path("tags")
                .get(0)
                .asText()).isEqualTo(ApiTags.TASKS);
        assertThat(callOperation.path("summary")
                .asText()).isEqualTo("Call a managed Task");
        assertThat(callOperation.path("responses").has("200")).isTrue();
        assertThat(document.path("paths")
                .path("/api/v1/tasks/{taskId}/results:load")
                .path("post")
                .path("tags")
                .get(0)
                .asText()).isEqualTo(ApiTags.TASKS);
        var exportOperation = document.path("paths")
                .path("/api/v1/tasks/{taskId}/results:export")
                .path("post");
        assertThat(exportOperation.path("tags")
                .get(0)
                .asText()).isEqualTo(ApiTags.TASKS);
        assertThat(exportOperation.path("responses")
                .path("200")
                .path("content")
                .has("application/x-ndjson")).isTrue();
        assertThat(document.path("paths").has(
                "/api/v1/task-batches/runs"
        )).isFalse();
        assertThat(document.path("paths").has(
                "/api/v1/runtime-view/tasks:preview"
        )).isTrue();
        assertThat(document.path("paths").has(
                "/api/v1/runtime-view/worker-groups/"
                        + "managed-tasks:batch-get"
        )).isFalse();
        var runtimePaths = new ArrayList<>(
                document.path("paths").propertyNames()
        );
        assertThat(runtimePaths)
                .noneMatch(path -> path.contains("configured"));
        assertThat(document.path("paths").has(
                "/api/v1/worker-groups/{workerGroupId}/tasks"
        )).isFalse();
        assertThat(document.path("paths").has(
                "/api/v1/worker-groups/{workerGroupId}/items:call"
        )).isFalse();
        assertThat(document.path("paths").has(
                "/api/v1/worker-groups/{workerGroupId}/item-results:load"
        )).isFalse();

        Path snapshot = Path.of(
                System.getProperty("xa.mass.repository.root")
        ).resolve("frontend/public/reference/openapi.json");
        assertThat(snapshot)
                .withFailMessage(
                        "OpenAPI snapshot is missing; run "
                                + ".\\gradlew.bat "
                                + ":server_jvm:exportOpenApiSnapshot"
                )
                .exists();
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                .withFailMessage(
                        "OpenAPI snapshot drifted; run "
                                + ".\\gradlew.bat "
                                + ":server_jvm:exportOpenApiSnapshot"
                )
                .isEqualTo(OpenApiSnapshotSupport.canonicalize(response.body()));
    }

    private static Set<String> expectedResponseCodes(String tag, String path) {
        if (!ApiTags.WORKER_DELIVERY.equals(tag)) {
            return Set.of("200", "400", "503");
        }
        if (path.endsWith("/direct-calls")) {
            return Set.of("200", "400", "429", "503");
        }
        if (path.endsWith("/commands:poll")) {
            return Set.of("200", "204", "400", "503");
        }
        if (path.endsWith("/workers/{workerId}/results")
                || path.endsWith("/results:append")) {
            return Set.of("202", "400", "503");
        }
        if (path.endsWith(":verify-binding")) {
            return Set.of("204", "400", "503");
        }
        if (path.endsWith("/commands:consume")) {
            return Set.of("200", "400", "503");
        }
        throw new AssertionError("Unclassified Worker Delivery path: " + path);
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
