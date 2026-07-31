package com.xa.mass.integration.phonenumber;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class PhoneNumberTaskMain {

    private static final System.Logger LOG =
            System.getLogger(PhoneNumberTaskMain.class.getName());

    private PhoneNumberTaskMain() {
    }

    public static void main(String[] arguments) throws IOException {
        CommandLineOptions options =
                CommandLineOptions.parse(arguments);
        Path seedPath = options.path("seed-path", "seed.txt")
                .toAbsolutePath()
                .normalize();
        Path resultPath = options.path("result-path", "result.txt")
                .toAbsolutePath()
                .normalize();
        if (seedPath.equals(resultPath)) {
            throw new IllegalArgumentException(
                    "seed-path and result-path must differ"
            );
        }

        List<String> numbers = readNumbers(seedPath);
        int workerCount = options.positiveInt(
                "worker-count",
                PhoneNumberIntegrationDefaults.WORKER_COUNT
        );
        String workerIdPrefix = options.string(
                "worker-id-prefix",
                PhoneNumberIntegrationDefaults.WORKER_ID_PREFIX
        );
        String taskId = options.string(
                "task-id",
                "phonenumber-rpc-" + System.currentTimeMillis()
        );
        long waitTimeoutMillis = options.positiveLong(
                "wait-timeout-millis",
                PhoneNumberIntegrationDefaults.RPC_WAIT_TIMEOUT_MILLIS
        );
        if (waitTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "--wait-timeout-millis must not exceed 60000"
            );
        }
        PhoneNumberTaskClient taskClient = new PhoneNumberTaskClient(
                new RuntimeApiHttpClient(
                        options.uri(
                                "server-base-url",
                                PhoneNumberIntegrationDefaults
                                        .SERVER_BASE_URL
                        ),
                        Duration.ofMillis(options.positiveLong(
                                "request-timeout-millis",
                                waitTimeoutMillis + 5_000
                        ))
                )
        );

        boolean created = false;
        List<String> results = new ArrayList<>(numbers.size());
        try {
            taskClient.createItemDrivenTask(
                    taskId,
                    options.string(
                            "worker-group-id",
                            PhoneNumberIntegrationDefaults
                                    .WORKER_GROUP_ID
                    ),
                    options.positiveLong(
                            "task-close-after-millis",
                            PhoneNumberIntegrationDefaults
                                    .TASK_CLOSE_AFTER_MILLIS
                    )
            );
            created = true;
            taskClient.approveTask(taskId);

            String defaultRegion =
                    options.string("default-region", "");

            for (int index = 0; index < numbers.size(); index++) {
                String messageId = taskId
                        + "-item-"
                        + String.format("%03d", index + 1);
                String workerId =
                        PhoneNumberIntegrationDefaults.workerId(
                                workerIdPrefix,
                                index % workerCount + 1
                        );
                results.add(taskClient.call(
                        taskId,
                        messageId,
                        workerId,
                        numbers.get(index),
                        defaultRegion,
                        waitTimeoutMillis
                ));
                int completed = index + 1;
                if (completed % 10 == 0
                        || completed == numbers.size()) {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "Completed "
                                    + completed
                                    + " of "
                                    + numbers.size()
                                    + " RPC calls"
                    );
                }
            }

            Path parent = resultPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(
                    resultPath,
                    results,
                    StandardCharsets.UTF_8
            );
            LOG.log(
                    System.Logger.Level.INFO,
                    "Wrote "
                            + results.size()
                            + " results to "
                            + resultPath
            );
        } finally {
            if (created) {
                try {
                    taskClient.closeTask(taskId);
                } catch (RuntimeException error) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Task close failed for taskId=" + taskId,
                            error
                    );
                }
            }
        }
    }

    private static List<String> readNumbers(Path path)
            throws IOException {
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            String value = line.trim();
            if (!value.isEmpty()) {
                numbers.add(value);
            }
        }
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException(
                    "seed.txt must contain at least one number"
            );
        }
        if (numbers.size() > 1_000) {
            throw new IllegalArgumentException(
                    "This integration accepts at most 1000 numbers"
            );
        }
        return List.copyOf(numbers);
    }
}
