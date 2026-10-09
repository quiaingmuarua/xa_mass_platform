package com.xa.mass.scenario.messages;

import com.xa.mass.server.project.*;
import com.xa.mass.server.task.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageScenarioPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessageScenarioProperties.class)
    static class Binding { }
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test void oneExplicitGroupDrivesDeclarationsAndServiceWithoutTheSharedStringBean() {
        runner.withPropertyValues("xa.mass.scenarios.messages.worker-group-id=bound-group").run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(MessageScenarioProperties.class);
            var assembly = new MessageCampaignsScenarioConfiguration();
            var definition = assembly.messageProject(config);
            var requirement = assembly.messageWorkerRequirements(config);
            assertThat(definition).isEqualTo(new ProjectDefinition("messages", List.of("bound-group")));
            assertThat(requirement.projectId()).isEqualTo(definition.projectId());
            assertThat(requirement.workerGroupId()).isEqualTo("bound-group");
            assertThat(requirement.eventCodes()).containsExactly("extension.worker.message.send");
            assertThat(requirement.pools()).containsExactly("messaging");
            assertThat(requirement.functions()).containsExactlyInAnyOrder("worker.messaging.available", "worker.messaging.phone");
            var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(definition));
            try (var service = assembly.messageTasks(directory, mock(ProjectTaskQueryService.class), mock(TaskCreationService.class),
                    mock(TaskDataService.class), mock(TaskLifecycleService.class), config, new com.xa.mass.server.operation.OperationGuard())) {
                service.start();
                assertThat(service.catalog().get("countries").toString()).contains("workerGroupId=bound-group").doesNotContain("demo-sim");
            }
        });
    }

    @Test void absentBlankOrUnknownSettingsFailWithoutAnImplicitGroup() {
        runner.withBean("scenarioWorkerGroup", String.class, () -> "demo-sim")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.messages.worker-group-id= ")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.messages.worker-group-id=g", "xa.mass.scenarios.messages.extra=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
