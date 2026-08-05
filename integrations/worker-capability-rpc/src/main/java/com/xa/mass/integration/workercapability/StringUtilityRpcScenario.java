package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class StringUtilityRpcScenario {

    private static final String WORKER_GROUP_ID =
            "scenario-string-utils-workers";
    private static final List<String> CLIENT_WORKER_KEYS = List.of(
            "scenario-string-utils-worker-001",
            "scenario-string-utils-worker-002",
            "scenario-string-utils-worker-003",
            "scenario-string-utils-worker-004",
            "scenario-string-utils-worker-005",
            "scenario-string-utils-worker-006",
            "scenario-string-utils-worker-007",
            "scenario-string-utils-worker-008",
            "scenario-string-utils-worker-009",
            "scenario-string-utils-worker-010"
    );
    private static final List<EventContract> EVENTS = List.of(
            new EventContract("string.md5", "md5"),
            new EventContract("string.sha1", "sha1"),
            new EventContract("string.base64.encode", "base64")
    );

    private final WorkerIdentityRegistrationClient identities;
    private final WorkerCapabilityTaskClient tasks;

    StringUtilityRpcScenario(
            WorkerIdentityRegistrationClient identities,
            WorkerCapabilityTaskClient tasks
    ) {
        this.identities = identities;
        this.tasks = tasks;
    }

    int run(
            String scenarioId,
            Path seedPath,
            Path outputPath,
            long waitTimeoutMillis,
            long taskCloseAfterMillis
    ) throws IOException {
        List<String> inputs = WorkerCapabilityInputs.readDistinct(
                seedPath,
                "string-seed.txt",
                CLIENT_WORKER_KEYS.size()
        );
        List<WorkerTarget> targets = resolveTargets();
        String taskId = scenarioId + "-string";
        boolean taskCreated = false;
        Throwable primaryFailure = null;
        try {
            tasks.createItemDrivenTask(
                    taskId,
                    WORKER_GROUP_ID,
                    taskCloseAfterMillis
            );
            taskCreated = true;
            tasks.approveTask(taskId);

            List<String> results = executeCalls(
                    taskId,
                    inputs,
                    targets,
                    waitTimeoutMillis
            );
            WorkerCapabilityResultWriter.writeAtomically(
                    outputPath,
                    results
            );
            return results.size();
        } catch (IOException | RuntimeException error) {
            primaryFailure = error;
            throw error;
        } finally {
            closeTask(taskId, taskCreated, primaryFailure);
        }
    }

    private List<WorkerTarget> resolveTargets() {
        List<WorkerTarget> targets = new ArrayList<>(
                CLIENT_WORKER_KEYS.size()
        );
        for (String clientWorkerKey : CLIENT_WORKER_KEYS) {
            targets.add(new WorkerTarget(
                    clientWorkerKey,
                    identities.registerOrRecoverWorkerId(
                            WORKER_GROUP_ID,
                            clientWorkerKey
                    )
            ));
        }
        return List.copyOf(targets);
    }

    private List<String> executeCalls(
            String taskId,
            List<String> inputs,
            List<WorkerTarget> targets,
            long waitTimeoutMillis
    ) {
        List<String> results = new ArrayList<>(
                EVENTS.size() * targets.size()
        );
        for (EventContract event : EVENTS) {
            for (int index = 0; index < targets.size(); index++) {
                WorkerTarget target = targets.get(index);
                Map<String, Object> input = Map.of(
                        "value",
                        inputs.get(index)
                );
                results.add(callAndEncode(
                        taskId,
                        target,
                        event,
                        input,
                        waitTimeoutMillis,
                        index + 1
                ));
            }
        }
        int expectedCount = EVENTS.size() * targets.size();
        if (results.size() != expectedCount) {
            throw new IllegalStateException(
                    "String scenario expected "
                            + expectedCount
                            + " results but received "
                            + results.size()
            );
        }
        return List.copyOf(results);
    }

    private String callAndEncode(
            String taskId,
            WorkerTarget target,
            EventContract event,
            Map<String, Object> input,
            long waitTimeoutMillis,
            int workerIndex
    ) {
        String messageId = messageId(taskId, event.eventCode(), workerIndex);
        Map<String, Object> result = tasks.call(
                taskId,
                messageId,
                event.eventCode(),
                target.workerId(),
                input,
                waitTimeoutMillis
        );
        requireValidResult(messageId, event, result);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("workerGroupId", WORKER_GROUP_ID);
        output.put("clientWorkerKey", target.clientWorkerKey());
        output.put("workerId", target.workerId());
        output.put("eventCode", event.eventCode());
        output.put("input", input);
        output.put("result", result);
        return Jsons.toJson(output);
    }

    private static void requireValidResult(
            String messageId,
            EventContract event,
            Map<String, Object> result
    ) {
        if (!Boolean.TRUE.equals(result.get("valid"))) {
            throw new IllegalStateException(
                    "RPC returned invalid domain output for " + messageId
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
    }

    private void closeTask(
            String taskId,
            boolean taskCreated,
            Throwable primaryFailure
    ) {
        if (!taskCreated) {
            return;
        }
        try {
            tasks.closeTask(taskId);
        } catch (RuntimeException closeFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private static String messageId(
            String taskId,
            String eventCode,
            int workerIndex
    ) {
        return taskId
                + "-"
                + eventCode.replace('.', '-')
                + "-"
                + String.format(Locale.ROOT, "%03d", workerIndex);
    }

    private record EventContract(
            String eventCode,
            String resultField
    ) {
    }
}
