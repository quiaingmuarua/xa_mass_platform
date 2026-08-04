package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class WorkerCapabilityRpcMain {

    private static final System.Logger LOG =
            System.getLogger(
                    WorkerCapabilityRpcMain.class.getName()
            );

    private WorkerCapabilityRpcMain() {
    }

    public static void main(String[] arguments) throws IOException {
        CommandLineOptions options =
                CommandLineOptions.parse(arguments);
        Path phoneSeedPath = options.path(
                        "phone-seed-path",
                        "phone-seed.txt"
                )
                .toAbsolutePath()
                .normalize();
        Path stringSeedPath = options.path(
                        "string-seed-path",
                        "string-seed.txt"
                )
                .toAbsolutePath()
                .normalize();
        Path resultPath = options.path(
                        "result-path",
                        "result.txt"
                )
                .toAbsolutePath()
                .normalize();
        if (resultPath.equals(phoneSeedPath)
                || resultPath.equals(stringSeedPath)) {
            throw new IllegalArgumentException(
                    "result-path must differ from both seed paths"
            );
        }

        List<String> phoneInputs = readDistinctInputs(
                phoneSeedPath,
                "phone-seed.txt"
        );
        List<String> stringInputs = readDistinctInputs(
                stringSeedPath,
                "string-seed.txt"
        );
        requireWorkerInputs(phoneInputs, "phone-seed.txt");
        requireWorkerInputs(stringInputs, "string-seed.txt");

        String scenarioId = RuntimeApiHttpClient.identifier(
                options.string(
                        "scenario-id",
                        "worker-capability-"
                                + System.currentTimeMillis()
                )
        );
        String phoneTaskId = scenarioId + "-phone";
        String stringTaskId = scenarioId + "-string";
        long waitTimeoutMillis = options.positiveLong(
                "wait-timeout-millis",
                WorkerCapabilityIntegrationDefaults
                        .RPC_WAIT_TIMEOUT_MILLIS
        );
        if (waitTimeoutMillis > 60_000) {
            throw new IllegalArgumentException(
                    "--wait-timeout-millis must not exceed 60000"
            );
        }
        WorkerCapabilityTaskClient taskClient =
                new WorkerCapabilityTaskClient(
                        new RuntimeApiHttpClient(
                                options.uri(
                                        "server-base-url",
                                        WorkerCapabilityIntegrationDefaults
                                                .SERVER_BASE_URL
                                ),
                                Duration.ofMillis(
                                        options.positiveLong(
                                                "request-timeout-millis",
                                                waitTimeoutMillis
                                                        + 5_000
                                        )
                                )
                        )
                );

        List<String> createdTasks = new ArrayList<>(2);
        Throwable primaryFailure = null;
        try {
            createAndApprove(
                    taskClient,
                    phoneTaskId,
                    WorkerCapabilityIntegrationDefaults
                            .PHONE_WORKER_GROUP_ID,
                    options,
                    createdTasks
            );
            createAndApprove(
                    taskClient,
                    stringTaskId,
                    WorkerCapabilityIntegrationDefaults
                            .STRING_WORKER_GROUP_ID,
                    options,
                    createdTasks
            );

            List<String> results = new ArrayList<>(60);
            runPhoneCalls(
                    taskClient,
                    phoneTaskId,
                    phoneInputs,
                    waitTimeoutMillis,
                    results
            );
            runStringCalls(
                    taskClient,
                    stringTaskId,
                    stringInputs,
                    waitTimeoutMillis,
                    results
            );
            if (results.size() != 60) {
                throw new IllegalStateException(
                        "Expected 60 results but received "
                                + results.size()
                );
            }
            writeResults(resultPath, results);
        } catch (IOException | RuntimeException error) {
            primaryFailure = error;
            throw error;
        } finally {
            RuntimeException closeFailure = closeTasks(
                    taskClient,
                    createdTasks
            );
            if (closeFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    private static void createAndApprove(
            WorkerCapabilityTaskClient taskClient,
            String taskId,
            String workerGroupId,
            CommandLineOptions options,
            List<String> createdTasks
    ) {
        taskClient.createItemDrivenTask(
                taskId,
                workerGroupId,
                options.positiveLong(
                        "task-close-after-millis",
                        WorkerCapabilityIntegrationDefaults
                                .TASK_CLOSE_AFTER_MILLIS
                )
        );
        createdTasks.add(taskId);
        taskClient.approveTask(taskId);
    }

    private static void runPhoneCalls(
            WorkerCapabilityTaskClient taskClient,
            String taskId,
            List<String> inputs,
            long waitTimeoutMillis,
            List<String> results
    ) {
        for (var event
                : WorkerCapabilityIntegrationDefaults.PHONE_EVENTS) {
            for (int index = 1;
                    index <= WorkerCapabilityIntegrationDefaults
                            .WORKER_COUNT;
                    index++) {
                String workerId =
                        WorkerCapabilityIntegrationDefaults.workerId(
                                WorkerCapabilityIntegrationDefaults
                                        .PHONE_WORKER_ID_PREFIX,
                                index
                        );
                Map<String, Object> input = Map.of(
                        "rawNumber",
                        inputs.get(index - 1)
                );
                results.add(callAndEncode(
                        taskClient,
                        taskId,
                        WorkerCapabilityIntegrationDefaults
                                .PHONE_WORKER_GROUP_ID,
                        workerId,
                        event,
                        input,
                        waitTimeoutMillis,
                        index
                ));
            }
        }
    }

    private static void runStringCalls(
            WorkerCapabilityTaskClient taskClient,
            String taskId,
            List<String> inputs,
            long waitTimeoutMillis,
            List<String> results
    ) {
        for (var event
                : WorkerCapabilityIntegrationDefaults.STRING_EVENTS) {
            for (int index = 1;
                    index <= WorkerCapabilityIntegrationDefaults
                            .WORKER_COUNT;
                    index++) {
                String workerId =
                        WorkerCapabilityIntegrationDefaults.workerId(
                                WorkerCapabilityIntegrationDefaults
                                        .STRING_WORKER_ID_PREFIX,
                                index
                        );
                Map<String, Object> input = Map.of(
                        "value",
                        inputs.get(index - 1)
                );
                results.add(callAndEncode(
                        taskClient,
                        taskId,
                        WorkerCapabilityIntegrationDefaults
                                .STRING_WORKER_GROUP_ID,
                        workerId,
                        event,
                        input,
                        waitTimeoutMillis,
                        index
                ));
            }
        }
    }

    private static String callAndEncode(
            WorkerCapabilityTaskClient taskClient,
            String taskId,
            String workerGroupId,
            String workerId,
            WorkerCapabilityIntegrationDefaults.EventContract event,
            Map<String, Object> input,
            long waitTimeoutMillis,
            int workerIndex
    ) {
        String messageId = taskId
                + "-"
                + event.eventCode().replace('.', '-')
                + "-"
                + String.format("%03d", workerIndex);
        Map<String, Object> result = taskClient.call(
                taskId,
                messageId,
                event.eventCode(),
                workerId,
                input,
                waitTimeoutMillis
        );
        if (!Boolean.TRUE.equals(result.get("valid"))) {
            throw new IllegalStateException(
                    "RPC returned invalid domain output for "
                            + messageId
            );
        }
        if (!result.containsKey(event.resultField())) {
            throw new IllegalStateException(
                    "RPC result is missing "
                            + event.resultField()
                            + " for "
                            + messageId
            );
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("workerGroupId", workerGroupId);
        output.put("workerId", workerId);
        output.put("eventCode", event.eventCode());
        output.put("input", input);
        output.put("result", result);
        return Jsons.toJson(output);
    }

    private static RuntimeException closeTasks(
            WorkerCapabilityTaskClient taskClient,
            List<String> taskIds
    ) {
        RuntimeException failure = null;
        for (String taskId : taskIds) {
            try {
                taskClient.closeTask(taskId);
            } catch (RuntimeException error) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Task close failed for taskId=" + taskId,
                        error
                );
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        return failure;
    }

    private static List<String> readDistinctInputs(
            Path path,
            String label
    ) throws IOException {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            String value = line.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must contain at least one value"
            );
        }
        if (values.size() > 1_000) {
            throw new IllegalArgumentException(
                    label + " accepts at most 1000 values"
            );
        }
        return List.copyOf(values);
    }

    private static void requireWorkerInputs(
            List<String> inputs,
            String label
    ) {
        if (inputs.size()
                < WorkerCapabilityIntegrationDefaults.WORKER_COUNT) {
            throw new IllegalArgumentException(
                    label
                            + " must contain at least "
                            + WorkerCapabilityIntegrationDefaults
                                    .WORKER_COUNT
                            + " distinct values"
            );
        }
    }

    private static void writeResults(
            Path path,
            List<String> results
    ) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, results, StandardCharsets.UTF_8);
        LOG.log(
                System.Logger.Level.INFO,
                "Wrote "
                        + results.size()
                        + " results to "
                        + path
        );
    }
}
