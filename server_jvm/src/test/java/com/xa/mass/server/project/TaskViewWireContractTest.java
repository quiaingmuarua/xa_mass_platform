package com.xa.mass.server.project;

import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class TaskViewWireContractTest {
    @Test void frontendFixtureIsTheCompleteSerializedServerDescriptor() throws Exception {
        var mapper = JsonMapper.builder().build();
        var task = new TaskView("task-fixture", "default", "demo-sim", "CLOSE_WHEN_IDLE", List.of(),
                Map.of("priority", "50", "maxRetryTimes", "3"), "Wire fixture", Map.of("source", "contract-fixture"));
        var fixture = Path.of(System.getProperty("xa.mass.repository.root"))
                .resolve("frontend/tests/fixtures/server-task-view.json");
        assertThat(mapper.readTree(mapper.writeValueAsString(task)))
                .isEqualTo(mapper.readTree(Files.readString(fixture)));
    }
}
