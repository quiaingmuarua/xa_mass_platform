package com.xa.mass.integration.workercapability;

import com.xa.mass.integration.workercapability.acceptance.CapabilityTaskEvidence;
import com.xa.mass.integration.workercapability.acceptance.CapabilityTaskEvidence.TaskSummary;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ExpectedItem;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ObservedResult;
import com.xa.mass.integration.workercapability.cli.CommandLineOptions;
import com.xa.mass.integration.workercapability.cli.WorkerCapabilityIntegrationDefaults;
import com.xa.mass.integration.workercapability.runtimeapi.FiniteTaskApiClient;
import com.xa.mass.integration.workercapability.runtimeapi.FiniteTaskApiClient.TaskItem;
import com.xa.mass.integration.workercapability.runtimeapi.RuntimeApiHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerCapabilityTaskMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerCapabilityTaskMain.class.getName()
    );
    private static final int APPEND_CHUNK_SIZE = 100;
    private static final List<GroupSpec> GROUPS = List.of(
            new GroupSpec(
                    "scenario-phone-number-workers",
                    "rawNumber",
                    "phone-seed.txt",
                    List.of(
                            "extension.worker.phonenumber.e164",
                            "extension.worker.phonenumber.country",
                            "extension.worker.phonenumber.original-carrier"
                    )
            ),
            new GroupSpec(
                    "scenario-string-utils-workers",
                    "value",
                    "string-seed.txt",
                    List.of(
                            "extension.worker.string.md5",
                            "extension.worker.string.sha1",
                            "extension.worker.string.base64.encode"
                    )
            )
    );

    private WorkerCapabilityTaskMain() {
    }

    public static void main(String[] arguments) throws IOException {
        CommandLineOptions options = CommandLineOptions.parse(arguments);
        String proofId = RuntimeApiHttpClient.identifier(options.string(
                "proof-id",
                "worker-capability-" + System.currentTimeMillis()
        ));
        Path phoneSeedPath = absolutePath(options.path(
                "phone-seed-path",
                "phone-seed.txt"
        ));
        Path stringSeedPath = absolutePath(options.path(
                "string-seed-path",
                "string-seed.txt"
        ));
        Path resultDirectory = absolutePath(options.path(
                "result-dir",
                "results"
        ));
        long maximumWaitMillis = options.positiveLong(
                "maximum-wait-millis",
                WorkerCapabilityIntegrationDefaults.MAXIMUM_WAIT_MILLIS
        );
        if (maximumWaitMillis > 300_000L) {
            throw new IllegalArgumentException(
                    "Task export wait must not exceed 5 minutes"
            );
        }
        long requestedTimeoutMillis = options.positiveLong(
                "request-timeout-millis",
                WorkerCapabilityIntegrationDefaults.REQUEST_TIMEOUT_MILLIS
        );
        long requestTimeoutMillis = Math.max(
                requestedTimeoutMillis,
                maximumWaitMillis + 5_000L
        );
        FiniteTaskApiClient tasks = new FiniteTaskApiClient(
                new RuntimeApiHttpClient(
                        options.uri(
                                "server-base-url",
                                WorkerCapabilityIntegrationDefaults
                                        .SERVER_BASE_URL
                        ),
                        Duration.ofMillis(requestTimeoutMillis)
                )
        );
        Path proofResultDirectory = resultDirectory.resolve(proofId);
        createProofResultDirectory(resultDirectory, proofResultDirectory);
        Path evidenceFile = proofResultDirectory.resolve(
                "capability-task-evidence.json"
        );

        List<TaskSummary> taskSummaries = new ArrayList<>();
        Map<String, ExpectedItem> manifest = new LinkedHashMap<>();
        List<ObservedResult> observedResults = new ArrayList<>();
        try {
            Map<String, List<String>> seeds = Map.of(
                    "phone-seed.txt",
                    readFirstTenLines(phoneSeedPath, "phone-seed.txt"),
                    "string-seed.txt",
                    readFirstTenLines(stringSeedPath, "string-seed.txt")
            );
            for (GroupSpec group : GROUPS) {
                runGroup(
                        tasks,
                        group,
                        seeds.get(group.seedName()),
                        maximumWaitMillis,
                        manifest,
                        observedResults,
                        taskSummaries
                );
            }
            WorkerCapabilityAcceptance.Summary summary =
                    WorkerCapabilityAcceptance.verify(
                            manifest,
                            observedResults
                    );
            CapabilityTaskEvidence.writeSucceeded(
                    evidenceFile,
                    proofId,
                    taskSummaries,
                    summary
            );
        } catch (IOException | RuntimeException error) {
            writeFailureEvidence(
                    evidenceFile,
                    proofId,
                    taskSummaries,
                    manifest,
                    observedResults,
                    error
            );
            throw error;
        }
        LOG.log(
                System.Logger.Level.INFO,
                "Verified 2 finite Tasks, 6 WorkerGroup/Event combinations "
                        + "and 60 successful Results; safe evidence="
                        + evidenceFile
        );
    }

    private static void runGroup(
            FiniteTaskApiClient tasks,
            GroupSpec group,
            List<String> lines,
            long maximumWaitMillis,
            Map<String, ExpectedItem> manifest,
            List<ObservedResult> observedResults,
            List<TaskSummary> taskSummaries
    ) {
        String taskId = tasks.createTask(group.workerGroupId());
        int summaryIndex = taskSummaries.size();
        taskSummaries.add(new TaskSummary(
                taskId,
                group.workerGroupId(),
                0,
                0
        ));

        List<TaskItem> items = buildItems(taskId, group, lines, manifest);
        for (int start = 0; start < items.size(); start += APPEND_CHUNK_SIZE) {
            tasks.appendItems(
                    taskId,
                    items.subList(
                            start,
                            Math.min(items.size(), start + APPEND_CHUNK_SIZE)
                    )
            );
        }
        taskSummaries.set(summaryIndex, new TaskSummary(
                taskId,
                group.workerGroupId(),
                items.size(),
                0
        ));
        tasks.approveTask(taskId);
        var exported = tasks.exportResults(taskId, maximumWaitMillis);
        if (!exported.ready()) {
            throw new IllegalStateException(
                    "Finite Task Results were not ready: " + taskId
            );
        }
        exported.results().forEach(result -> observedResults.add(
                new ObservedResult(
                        result.messageId(),
                        result.opaqueResultPayload()
                )
        ));
        taskSummaries.set(summaryIndex, new TaskSummary(
                taskId,
                group.workerGroupId(),
                items.size(),
                exported.results().size()
        ));
    }

    private static List<TaskItem> buildItems(
            String taskId,
            GroupSpec group,
            List<String> lines,
            Map<String, ExpectedItem> manifest
    ) {
        List<TaskItem> items = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            for (String eventCode : group.eventCodes()) {
                String messageId = taskId
                        + "-"
                        + normalizeEvent(eventCode)
                        + "-"
                        + String.format("%03d", lineIndex + 1);
                if (manifest.putIfAbsent(
                        messageId,
                        new ExpectedItem(group.workerGroupId(), eventCode)
                ) != null) {
                    throw new IllegalStateException(
                            "Capability Task messageId is not unique"
                    );
                }
                items.add(new TaskItem(
                        messageId,
                        eventCode,
                        Map.of(group.payloadKey(), lines.get(lineIndex))
                ));
            }
        }
        return List.copyOf(items);
    }

    private static String normalizeEvent(String eventCode) {
        return eventCode.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> readFirstTenLines(
            Path path,
            String label
    ) throws IOException {
        List<String> lines = Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        );
        if (lines.size() < 10) {
            throw new IllegalArgumentException(
                    label + " must contain at least 10 lines"
            );
        }
        return List.copyOf(lines.subList(0, 10));
    }

    private static Path absolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void createProofResultDirectory(
            Path resultDirectory,
            Path proofResultDirectory
    ) throws IOException {
        if (Files.exists(proofResultDirectory)) {
            throw new IllegalArgumentException(
                    "Proof result directory already exists: "
                            + proofResultDirectory
            );
        }
        Files.createDirectories(resultDirectory);
        Files.createDirectory(proofResultDirectory);
    }

    private static void writeFailureEvidence(
            Path evidenceFile,
            String proofId,
            List<TaskSummary> tasks,
            Map<String, ExpectedItem> manifest,
            List<ObservedResult> results,
            Throwable primaryFailure
    ) {
        try {
            RuntimeException failure = primaryFailure
                    instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Capability Task proof failed",
                            primaryFailure
                    );
            CapabilityTaskEvidence.writeFailed(
                    evidenceFile,
                    proofId,
                    tasks,
                    manifest,
                    results,
                    failure
            );
        } catch (IOException | RuntimeException evidenceFailure) {
            primaryFailure.addSuppressed(evidenceFailure);
        }
    }

    private record GroupSpec(
            String workerGroupId,
            String payloadKey,
            String seedName,
            List<String> eventCodes
    ) {

        private GroupSpec {
            eventCodes = List.copyOf(eventCodes);
        }
    }
}
