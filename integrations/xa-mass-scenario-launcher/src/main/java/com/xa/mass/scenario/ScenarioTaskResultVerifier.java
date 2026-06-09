package com.xa.mass.scenario;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskResultItem;
import com.xa.mass.client.task.TaskResultReadRequest;
import com.xa.mass.client.task.TaskResultWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class ScenarioTaskResultVerifier {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500L);

    private final ScenarioClientFactory clientFactory;
    private final ScenarioLauncherOptions options;

    ScenarioTaskResultVerifier(ScenarioClientFactory clientFactory, ScenarioLauncherOptions options) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
        this.options = Objects.requireNonNull(options, "options is required");
    }

    void waitForVisibleSuccess(List<TaskScenarioSeeder.SeededTask> tasks) throws InterruptedException {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        Instant deadline = Instant.now().plus(options.resultWaitTimeout());
        for (TaskScenarioSeeder.SeededTask task : tasks) {
            waitForTask(task, deadline);
        }
    }

    private void waitForTask(TaskScenarioSeeder.SeededTask task, Instant deadline) throws InterruptedException {
        MassPlatform client = clientFactory.forApiKey(task.taskApiKey());
        int lastVisible = 0;
        while (Instant.now().isBefore(deadline)) {
            TaskResultWindow window = client.tasks().results(task.taskId(), TaskResultReadRequest.builder()
                    .limit(100)
                    .build());
            List<TaskResultItem> items = window.items() == null ? List.of() : window.items();
            lastVisible = items.size();
            long successCount = items.stream()
                    .filter(item -> "SUCCESS".equalsIgnoreCase(item.status()))
                    .count();
            if (successCount > 0) {
                System.out.printf("[java-scenario-task-launcher] visible success taskId=%s success=%d visible=%d%n",
                        task.taskId(), successCount, items.size());
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("timed out waiting for visible SUCCESS result taskId="
                + task.taskId() + " visible=" + lastVisible + " timeout=" + options.resultWaitTimeout());
    }
}
