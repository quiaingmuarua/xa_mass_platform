package com.xa.mass.server.project;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectDirectoryTest {
    @Test void declarationsAreImmutableAndPairCoordinatesAreStableAndUnambiguous() {
        var properties = new ProjectAssemblyProperties(List.of(
                new ProjectAssemblyProperties.Project("a", List.of("b-c", "shared")),
                new ProjectAssemblyProperties.Project("a-b", List.of("c", "shared"))));
        var directory = new ProjectDirectory(properties);
        var restart = new ProjectDirectory(properties);
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
        assertThatThrownBy(() -> new ProjectAssemblyProperties.Project(" ", List.of("g")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectAssemblyProperties.Project("p", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectAssemblyProperties.Project("p", List.of("g", "g")))
                .isInstanceOf(IllegalArgumentException.class);
        var project = new ProjectAssemblyProperties.Project("p", List.of("g"));
        assertThatThrownBy(() -> new ProjectAssemblyProperties(List.of(project, project)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
