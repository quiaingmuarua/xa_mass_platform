package com.xa.mass.sms.backend;

import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;

import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsProfileTest {
    @Test void inactiveProfileRegistersNoProductOrGroups() {
        var registrations = mock(WorkerGroupRegistrationService.class);
        var submissions = mock(TaskCallSubmissionService.class);
        var results = mock(TaskDataService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WorkerGroupRegistrationService.class, () -> registrations);
            context.registerBean(TaskCallSubmissionService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.register(SmsProductConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(ListenerService.class)).isEmpty();
            assertThat(context.getBeansOfType(ProductController.class)).isEmpty();
            assertThat(context.getBeansOfType(SmsFrontendController.class)).isEmpty();
            verifyNoInteractions(registrations, submissions, results);
        }
    }

    @Test void productionUsesOnlyTheApprovedServerApplicationCapabilities() throws Exception {
        assertThat(Files.readString(Path.of("build.gradle")))
                .contains("implementation project(':server_jvm')", "id 'java-library'")
                .doesNotContain("id 'org.springframework.boot'", "project(':distribution:");
        Set<String> allowed = Set.of("com.xa.mass.server.worker.group.WorkerGroupRegistrationService",
                "com.xa.mass.server.task.call.TaskCallSubmissionService", "com.xa.mass.server.task.TaskDataService",
                "com.xa.mass.server.api.v1.contract.task.TaskItemRequest",
                "com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse",
                "com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus");
        var pattern = Pattern.compile("import\\s+(com\\.xa\\.mass\\.server\\.[\\w.*]+)\\s*;");
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertThat(source).doesNotContain("java.net.http", "java.net.URL", "RestClient", "WebClient",
                        "com.xa.mass.kernel", "com.xa.mass.matching", "org.springframework.data.redis", "io.lettuce",
                        "SpringApplication", "public static void main(");
                var matcher = pattern.matcher(source);
                while (matcher.find()) assertThat(allowed).contains(matcher.group(1));
            }
        }
    }
}
