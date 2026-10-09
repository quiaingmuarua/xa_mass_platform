package com.xa.mass.scenario.messages;

import com.xa.mass.server.project.*;
import com.xa.mass.server.task.*;
import java.util.List;
import java.util.ArrayList;
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
    private static String[] settings() {
        return new String[]{"xa.mass.scenarios.messages.applications[0].id=custom",
                "xa.mass.scenarios.messages.applications[0].label=Custom",
                "xa.mass.scenarios.messages.applications[0].worker-group-id=bound-group",
                "xa.mass.scenarios.messages.applications[1].id=other",
                "xa.mass.scenarios.messages.applications[1].label=Other",
                "xa.mass.scenarios.messages.applications[1].worker-group-id=another-group"};
    }
    @Test void explicitApplicationsDriveDeclarationsAndServiceWithoutTheSharedStringBean() {
        runner.withPropertyValues(settings()).run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(MessageScenarioProperties.class);
            var assembly = new MessageCampaignsScenarioConfiguration();
            var definition = assembly.messageProject(config);
            var requirement = assembly.messageWorkerRequirements(config);
            assertThat(definition).isEqualTo(new ProjectDefinition("messages", List.of("bound-group", "another-group")));
            assertThat(requirement.projectId()).isEqualTo(definition.projectId());
            assertThat(requirement.workerGroupIds()).containsExactly("bound-group", "another-group");
            assertThat(requirement.eventCodes()).containsExactly("extension.worker.message.send");
            assertThat(requirement.pools()).containsExactly("messaging");
            assertThat(requirement.functions()).containsExactly("worker.messaging.available");
            var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(definition));
            try (var service = assembly.messageTasks(directory, mock(ProjectTaskQueryService.class), mock(TaskCreationService.class),
                    mock(TaskDataService.class), mock(TaskLifecycleService.class), config, new com.xa.mass.server.operation.OperationGuard())) {
                service.start();
                assertThat(service.catalog().get("applications")).isEqualTo(config.applications());
                assertThat(service.catalog().get("countries").toString()).doesNotContain("workerGroupId");
            }
        });
    }
    @Test void invalidAndOldSettingsFailWithoutImplicitApplications() {
        runner.withBean("scenarioWorkerGroup", String.class, () -> "demo-sim").run(context -> assertThat(context).hasFailed());
        for (String invalid : List.of("worker-group-id=demo-sim", "extra=true", "applications[0].extra=true",
                "applications[0].label= ", "applications[1].id=custom", "applications[1].worker-group-id=bound-group"))
            runner.withPropertyValues(settings()).withPropertyValues("xa.mass.scenarios.messages." + invalid)
                    .run(context -> assertThat(context).hasFailed());
    }
    @Test void immutableApplicationsRetainOrderAndDisplayLabelsDoNotChangeIdentities() {
        var apps = new ArrayList<>(List.of(new MessageScenarioProperties.Application("a", "Before", "g")));
        var config = new MessageScenarioProperties(apps); apps.clear();
        assertThat(config.workerGroupIds()).containsExactly("g");
        assertThatThrownBy(() -> config.applications().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MessageScenarioProperties(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(config.findApplication("missing")).isEmpty();
    }
}
