package com.xa.mass.serverboot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioArchitectureTest {
    @Test void scenariosUseOnlyTheirExistingServerApplicationCapabilities() throws Exception {
        var allowed = Map.of(
                "sms-reception-jvm", Set.of("worker.group.WorkerGroupRegistrationService",
                        "task.call.TaskCallSubmissionService", "task.TaskDataService",
                        "api.v1.contract.task.TaskItemRequest", "api.v1.contract.task.TaskItemResultResponse",
                        "api.v1.contract.task.TaskItemResultStatus"),
                "message-campaigns-jvm", Set.of("worker.group.WorkerGroupRegistrationService",
                        "task.TaskCreationService", "task.TaskDataService", "task.TaskLifecycleService",
                        "api.v1.contract.task.TaskCreateRequest", "api.v1.contract.task.TaskItemRequest",
                        "api.v1.contract.task.TaskItemResultResponse", "api.v1.contract.task.TaskItemResultStatus"));
        var imports = Pattern.compile("import\\s+com\\.xa\\.mass\\.server\\.([\\w.*]+)\\s*;");
        for (var module : allowed.entrySet()) {
            Path directory = Path.of("../scenarios", module.getKey());
            assertThat(Files.readString(directory.resolve("build.gradle")))
                    .contains("id 'java-library'", "implementation project(':server_jvm')")
                    .doesNotContain("id 'org.springframework.boot'", "project(':server_boot_jvm')",
                            "project(':distribution:", "project(':scenarios:");
            try (var paths = Files.walk(directory.resolve("src/main/java"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    assertThat(source).as(path.toString()).doesNotContain("SpringApplication", "@Profile",
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
