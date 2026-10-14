package com.xa.mass.serverboot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioArchitectureTest {
    @Test void configurationDatabaseBelongsOnlyToTheAppChecksModule() throws Exception {
        assertThat(Files.readString(Path.of("../scenarios/app-checks-jvm/build.gradle"))).contains("runtimeOnly 'com.h2database:h2'");
        for (String module : Set.of("kernel_jvm", "kernel_pacer_jvm", "worker_matching_jvm", "server_jvm", "server_boot_jvm")) {
            Path directory = Path.of("..", module);
            assertThat(Files.readString(directory.resolve("build.gradle"))).doesNotContain("com.h2database", "spring-boot-starter-jdbc");
            try (var paths = Files.walk(directory.resolve("src/main/java"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList())
                    assertThat(Files.readString(path)).as(path.toString()).doesNotContain("java.sql.", "org.h2.", "AppCheckConfigurationStore");
            }
        }
    }

    @Test void messageRulesAndViewsDoNotDependOnTheApplicationCoordinator() throws Exception {
        Path root = Path.of("../scenarios/message-campaigns-jvm/src/main/java/com/xa/mass/scenario/messages");
        for (String file : Set.of("MessageInputs.java", "MessageSpecification.java", "MessageRecipientFile.java",
                "MessageViews.java", "MessageError.java"))
            assertThat(Files.readString(root.resolve(file))).as(file)
                    .doesNotContain("MessageTaskService", "SmartLifecycle", "OperationGuard", "TaskCreationService",
                            "TaskLifecycleService", "org.springframework");
    }

    @Test void scenariosUseOnlyTheirExistingServerApplicationCapabilities() throws Exception {
        var allowed = Map.of(
                "app-checks-jvm", Set.of("task.access.TaskCaller", "project.ProjectDirectory", "project.ProjectTaskQueryService", "project.ProjectDefinition", "project.ProjectWorkerRequirements",
                        "task.TaskCreationService", "task.TaskCreationUnconfirmedException", "task.TaskDataService", "task.directory.TaskUserId",
                        "operation.OperationGuard", "operation.OperationAlreadyRunningException", "task.result.TaskResultsExportService", "api.v1.contract.ActionOutcome", "api.v1.contract.task.TaskItemResultResponse", "api.v1.contract.task.TaskCreateRequest",
                        "api.v1.contract.task.TaskCreateResponse", "api.v1.contract.task.TaskItemRequest",
                        "api.v1.contract.task.TaskItemResultStatus", "api.v1.contract.runtimeview.TaskView", "worker.observation.WorkerPropertyProjection"),
                "sms-reception-jvm", Set.of("task.access.TaskCaller", "task.call.TaskRpcPreparationService", "api.v1.contract.task.TaskRpcPrepareRequest",
                        "task.call.TaskRpcCallService", "task.TaskDataService", "task.observation.TaskLeaseProjection",
                        "task.observation.TaskLeaseProjectionMetrics", "api.v1.contract.task.TaskRpcCallRequest",
                        "api.v1.contract.task.TaskItemRequest", "api.v1.contract.task.TaskItemResultResponse",
                        "api.v1.contract.task.TaskItemResultStatus"),
                "message-campaigns-jvm", Set.of("task.access.TaskCaller", "project.ProjectDirectory",
                        "project.ProjectDefinition", "project.ProjectWorkerRequirements", "operation.OperationGuard", "error.ServerException", "api.v1.contract.ActionOutcome",
                        "project.ProjectTaskQueryService", "task.TaskCreationUnconfirmedException",
                        "task.TaskCreationService", "task.TaskDataService",
                        "api.v1.contract.task.TaskCreateRequest", "api.v1.contract.task.TaskItemRequest",
                        "api.v1.contract.task.TaskItemResultResponse", "api.v1.contract.task.TaskItemResultStatus"));
        var imports = Pattern.compile("import\\s+com\\.xa\\.mass\\.server\\.([\\w.*]+)\\s*;");
        for (var module : allowed.entrySet()) {
            Path directory = Path.of("../scenarios", module.getKey());
            assertThat(Files.readString(directory.resolve("build.gradle")))
                    .contains("id 'java-library'", "implementation project(':server_jvm')")
                    .doesNotContain("id 'org.springframework.boot'", "project(':server_boot_jvm')",
                            "project(':distribution:", "project(':scenarios:");
            if (Set.of("app-checks-jvm", "message-campaigns-jvm", "sms-reception-jvm").contains(module.getKey()))
                assertThat(Files.readString(directory.resolve("build.gradle")))
                        .contains("implementation project(':worker_matching_jvm')");
            try (var paths = Files.walk(directory.resolve("src/main/java"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    String checked = switch (module.getKey()) {
                        case "app-checks-jvm" -> source.replaceAll("com\\.xa\\.mass\\.workermatching\\.FixedWindowPoolDefinition(?:\\.WindowLimit)?\\b", "public-window-definition-contract");
                        case "message-campaigns-jvm" -> source.replaceAll("com\\.xa\\.mass\\.workermatching\\.QualifiedCountryDefinition\\b", "public-qualified-country-contract");
                        case "sms-reception-jvm" -> source
                                .replace("com.xa.mass.workermatching.PartitionedLeasePoolDefinition", "public-lease-definition-contract")
                                .replace("com.xa.mass.workermatching.PlatformLeaseState.Coordinate", "public-lease-coordinate-value");
                        default -> source;
                    };
                    assertThat(checked.replace("com.xa.mass.kernel.assignment.RefillTarget", "public-refill-target-contract")
                            .replace("com.xa.mass.kernel.assignment.EligibilityQuery", "public-refill-query-contract")
                            .replace("com.xa.mass.kernel.assignment.WorkerQuery", "public-item-query-contract")).as(path.toString()).doesNotContain("SpringApplication", "@Profile",
                            "public static void main(", "com.xa.mass.serverboot", "io.lettuce",
                            "org.springframework.data.redis", "com.xa.mass.kernel", "com.xa.mass.workermatching",
                            "java.net.http", "RestClient", "WebClient");
                    var matcher = imports.matcher(source);
                    while (matcher.find()) assertThat(module.getValue()).as(path.toString()).contains(matcher.group(1));
                }
            }
        }
    }
}
