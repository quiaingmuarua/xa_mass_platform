package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerLabConvergenceArchitectureTest {

    @Test
    void integrationUsesOnlyPublicHttpAndDeliveryJsonSupport()
            throws IOException {
        Path project = Path.of("").toAbsolutePath().normalize();
        String build = Files.readString(project.resolve("build.gradle"));
        String sources;
        try (var files = Files.walk(project.resolve("src/main/java"))) {
            sources = files.filter(Files::isRegularFile)
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertThat(build)
                .contains("project(':transport:worker-delivery-contract')")
                .contains("runWorkerStateConvergence")
                .contains("runWorkerTaskFaultConvergence")
                .contains("runWorkerConvergenceCampaign")
                .doesNotContain("runWorkerLabReliability")
                .doesNotContain("scenario_workers_jvm")
                .doesNotContain("server_jvm")
                .doesNotContain("kernel_jvm")
                .doesNotContain("netty-adapter");
        assertThat(sources)
                .doesNotContain("com.xa.mass.scenarioworkers")
                .doesNotContain("com.xa.mass.server")
                .doesNotContain("com.xa.mass.kernel")
                .doesNotContain("com.xa.mass.adapter")
                .doesNotContain("com.xa.mass.worker.javase")
                .doesNotContain("class WorkerLabReliability ");

        String campaign = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/integration/workerlab/"
                        + "WorkerConvergenceCampaign.java"
        ));
        String state = Files.readString(project.resolve(
                "src/main/java/com/xa/mass/integration/workerlab/"
                        + "WorkerStateConvergence.java"
        ));
        String finalWorldObservation = between(
                campaign,
                "private static FinalWorld observeFinalWorld(",
                "private static TaskSummary observeTasks("
        );
        String taskObservation = between(
                campaign,
                "private static TaskSummary observeTasks(",
                "private static TaskProof createSlotTask("
        );
        assertThat(campaign)
                .contains("operation-not-established")
                .contains("partialWorldEvaluation");
        assertThat(finalWorldObservation)
                .doesNotContain("WorkerLabControlClient")
                .doesNotContain("lab.");
        assertThat(taskObservation)
                .doesNotContain("WorkerLabControlClient")
                .doesNotContain("lab.");
        assertThat(state)
                .contains("operation-accepted")
                .doesNotContain("MutationNotEstablishedException");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String between(
            String source,
            String startMarker,
            String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
