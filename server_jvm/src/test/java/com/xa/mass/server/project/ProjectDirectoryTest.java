package com.xa.mass.server.project;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class ProjectDirectoryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProjectAssemblyProperties.class)
    @Import(ProjectDirectory.class)
    static class Binding { }

    @Test void configurationListOverrideRetainsModuleProjectsAndCrossSourceDuplicatesFailStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withBean(ProjectDefinition.class, () -> new ProjectDefinition("messages", List.of("demo-sim")))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(new MapPropertySource("host", java.util.Map.of(
                        "xa.mass.project-assembly.projects[0].project-id", "sms",
                        "xa.mass.project-assembly.projects[0].worker-group-ids[0]", "demo-sim"))));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("sms", "messages");
        });
        runner.withPropertyValues("xa.mass.project-assembly.projects[0].project-id=replacement",
                "xa.mass.project-assembly.projects[0].worker-group-ids[0]=new-group").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("replacement", "messages");
        });
        runner.withPropertyValues("xa.mass.project-assembly.projects[0].project-id=messages",
                "xa.mass.project-assembly.projects[0].worker-group-ids[0]=demo-sim").run(context ->
                assertThat(context.getStartupFailure()).hasStackTraceContaining("Duplicate Project ID across startup declarations: messages"));
    }

    @Test void moduleAndConfiguredProjectsUseTheSameStableCoordinatesAndRejectAllDuplicates() {
        var messages = new ProjectDefinition("messages", List.of("demo-sim"));
        var yaml = new ProjectDirectory(new ProjectAssemblyProperties(List.of(messages)), List.of());
        var module = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(messages));
        assertThat(module.projects()).isEqualTo(yaml.projects());
        assertThat(module.requireManagedTaskId("messages", "demo-sim")).isEqualTo("project-rpc-bWVzc2FnZXM.ZGVtby1zaW0");
        var combined = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("sms", List.of("demo-sim")))), List.of(messages));
        assertThat(combined.projects()).containsOnlyKeys("sms", "messages");
        assertThatThrownBy(() -> new ProjectDirectory(new ProjectAssemblyProperties(List.of(messages)), List.of(messages)))
                .hasMessageContaining("Duplicate Project ID").hasMessageContaining("messages");
        assertThatThrownBy(() -> new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of(messages, messages)))
                .hasMessageContaining("Duplicate Project ID");
        assertThatThrownBy(() -> new ProjectDirectory(new ProjectAssemblyProperties(List.of(messages)),
                List.of(new ProjectDefinition("messages", List.of("other")))))
                .hasMessageContaining("Duplicate Project ID");
    }

    @Test void declarationsAreImmutableAndPairCoordinatesAreStableAndUnambiguous() {
        var properties = new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("a", List.of("b-c", "shared")),
                new ProjectDefinition("a-b", List.of("c", "shared"))));
        var directory = new ProjectDirectory(properties, java.util.List.of());
        var restart = new ProjectDirectory(properties, java.util.List.of());
        assertThat(directory.projects()).isEqualTo(restart.projects());
        assertThat(directory.requireManagedTaskId("a", "shared"))
                .isNotEqualTo(directory.requireManagedTaskId("a-b", "shared"));
        assertThat(directory.requireManagedTaskId("a", "b-c"))
                .isNotEqualTo(directory.requireManagedTaskId("a-b", "c"));
        assertThatThrownBy(() -> directory.projects().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> directory.require("absent")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> directory.requireManagedTaskId("a", "c"))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
    }

    @Test void invalidConfigurationFailsWithoutInventingDefaults() {
        assertThat(new ProjectAssemblyProperties(null).projects()).isEmpty();
        assertThatThrownBy(() -> new ProjectDefinition(" ", List.of("g")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectDefinition("p", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectDefinition("p", List.of("g", "g")))
                .isInstanceOf(IllegalArgumentException.class);
        var project = new ProjectDefinition("p", List.of("g"));
        assertThatThrownBy(() -> new ProjectAssemblyProperties(List.of(project, project)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
