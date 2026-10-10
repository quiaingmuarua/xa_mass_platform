package com.xa.mass.scenario.sms;

import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus;

import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.observation.TaskLeaseProjectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsProfileTest {
    @Test void platformWithoutScenarioImportRegistersNoBusinessOrGroups() {
        var registrations = mock(ProjectDirectory.class);
        var submissions = mock(TaskRpcCallService.class);
        var results = mock(TaskDataService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProjectDirectory.class, () -> registrations);
            context.registerBean(TaskRpcCallService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.registerBean(TaskLeaseProjectionService.class, () -> mock(TaskLeaseProjectionService.class));
            context.refresh();
            assertThat(context.getBeansOfType(SmsReceptionService.class)).isEmpty();
            assertThat(context.getBeansOfType(SmsController.class)).isEmpty();
            verifyNoInteractions(registrations, submissions, results);
        }
    }

    @Test void explicitImportStartsTheScenarioWithoutAnEnvironmentProfile() {
        var registrations = mock(ProjectDirectory.class);
        var submissions = mock(TaskRpcCallService.class);
        var results = mock(TaskDataService.class);
        var events = List.of("extension.worker.sms.number.lease");
        when(registrations.requireManagedTaskId("sms", "demo-sim"))
                .thenReturn("managed");
        SmsReceptionService service;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProjectDirectory.class, () -> registrations);
            context.registerBean(TaskRpcCallService.class, () -> submissions);
            context.registerBean(TaskDataService.class, () -> results);
            context.registerBean(TaskLeaseProjectionService.class, () -> mock(TaskLeaseProjectionService.class));
            context.registerBean("scenarioWorkerGroup", String.class, () -> "demo-sim");
            context.registerBean("scenarioWorkerEvents", List.class, () -> events);
            context.register(SmsScenarioConfiguration.class);
            context.refresh();
            service = context.getBean(SmsReceptionService.class);
            assertThat(service.isRunning()).isTrue();
            assertThat(context.getBeansOfType(SmsController.class)).hasSize(1);
            verify(registrations).requireManagedTaskId("sms", "demo-sim");
        }
        assertThat(service.isRunning()).isFalse();
    }

    @Test void productionUsesOnlyTheApprovedServerApplicationCapabilities() throws Exception {
        assertThat(Files.readString(Path.of("build.gradle")))
                .contains("implementation project(':server_jvm')", "id 'java-library'")
                .doesNotContain("id 'org.springframework.boot'", "project(':distribution:", "project(':server_boot_jvm')");
        Set<String> allowed = Set.of("com.xa.mass.server.project.ProjectDirectory",
                "com.xa.mass.server.task.call.TaskRpcCallService", "com.xa.mass.server.task.TaskDataService",
                "com.xa.mass.server.task.observation.TaskLeaseProjection", "com.xa.mass.server.task.observation.TaskLeaseProjectionMetrics",
                "com.xa.mass.server.api.v1.contract.task.TaskRpcCallRequest", "com.xa.mass.server.api.v1.contract.task.TaskItemRequest",
                "com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse",
                "com.xa.mass.server.api.v1.contract.task.TaskItemResultStatus");
        var pattern = Pattern.compile("import\\s+(com\\.xa\\.mass\\.server\\.[\\w.*]+)\\s*;");
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertThat(source.replace("com.xa.mass.kernel.assignment.WorkerQuery", "public-query-contract")).doesNotContain("java.net.http", "java.net.URL", "RestClient", "WebClient",
                        "com.xa.mass.kernel", "com.xa.mass.workermatching.storage", "com.xa.mass.workermatching.pool", "com.xa.mass.workermatching.MatchingComposition", "org.springframework.data.redis", "io.lettuce",
                        "SpringApplication", "public static void main(");
                var matcher = pattern.matcher(source);
                while (matcher.find()) assertThat(allowed).contains(matcher.group(1));
            }
        }
    }
}
