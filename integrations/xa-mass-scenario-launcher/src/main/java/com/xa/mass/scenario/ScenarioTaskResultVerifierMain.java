package com.xa.mass.scenario;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.http.exception.MassHttpException;
import com.xa.mass.client.task.TaskResultItem;
import com.xa.mass.client.task.TaskResultReadRequest;
import com.xa.mass.client.task.TaskResultWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ScenarioTaskResultVerifierMain {
    private ScenarioTaskResultVerifierMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            System.out.println(helpText());
            return;
        }
        MassPlatform client = MassPlatform.builder()
                .baseUrl(options.baseUrl)
                .apiKey(options.taskApiKey)
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        try {
            waitForVisibleSuccess(client, options.taskId, options.timeout);
        } catch (MassHttpException e) {
            throw new IllegalStateException("task result verification failed: " + e.getMessage(), e);
        }
    }

    private static void waitForVisibleSuccess(MassPlatform client, String taskId, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        int lastVisible = 0;
        while (Instant.now().isBefore(deadline)) {
            TaskResultWindow window = client.tasks().forTask(taskId).results(TaskResultReadRequest.builder()
                    .afterSeq(0L)
                    .limit(100)
                    .build());
            List<TaskResultItem> items = window.items() == null ? List.of() : window.items();
            lastVisible = items.size();
            long successCount = items.stream()
                    .filter(item -> "SUCCESS".equalsIgnoreCase(item.status()))
                    .count();
            if (successCount > 0) {
                System.out.printf("[java-scenario-task-verifier] visible success taskId=%s success=%d visible=%d%n",
                        taskId, successCount, items.size());
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("timed out waiting for visible SUCCESS result taskId="
                + taskId + " visible=" + lastVisible + " timeout=" + timeout);
    }

    private static String helpText() {
        return """
                Usage:
                  java -cp integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar \\
                    com.xa.mass.scenario.ScenarioTaskResultVerifierMain \\
                    --base-url http://127.0.0.1:8088 --task-api-key <key> --task-id <task-id>

                Waits until the task result API exposes at least one SUCCESS result.
                """;
    }

    private record Options(String baseUrl,
                           String taskApiKey,
                           String taskId,
                           Duration timeout,
                           boolean help) {
        static Options parse(String[] args) {
            String baseUrl = "http://127.0.0.1:8088";
            String taskApiKey = System.getenv("MASS_TASK_API_KEY");
            String taskId = null;
            int timeoutSeconds = 60;
            boolean help = false;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--base-url".equals(arg)) {
                    baseUrl = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--base-url=")) {
                    baseUrl = arg.substring("--base-url=".length());
                } else if ("--task-api-key".equals(arg)) {
                    taskApiKey = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--task-api-key=")) {
                    taskApiKey = arg.substring("--task-api-key=".length());
                } else if ("--task-id".equals(arg)) {
                    taskId = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--task-id=")) {
                    taskId = arg.substring("--task-id=".length());
                } else if ("--timeout-seconds".equals(arg)) {
                    timeoutSeconds = Integer.parseInt(requiredArg(args, index, arg));
                    index++;
                } else if (arg.startsWith("--timeout-seconds=")) {
                    timeoutSeconds = Integer.parseInt(arg.substring("--timeout-seconds=".length()));
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (!help && (taskApiKey == null || taskApiKey.isBlank())) {
                throw new IllegalArgumentException("--task-api-key or MASS_TASK_API_KEY is required");
            }
            if (!help && (taskId == null || taskId.isBlank())) {
                throw new IllegalArgumentException("--task-id is required");
            }
            return new Options(baseUrl, taskApiKey, taskId, Duration.ofSeconds(Math.max(1, timeoutSeconds)), help);
        }

        private static String requiredArg(String[] args, int index, String name) {
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index + 1];
        }
    }
}
