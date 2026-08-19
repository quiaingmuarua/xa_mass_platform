package com.xa.mass.integration.workercapability;

import com.xa.mass.integration.workercapability.cli.CommandLineOptions;
import com.xa.mass.integration.workercapability.cli.WorkerCapabilityIntegrationDefaults;
import com.xa.mass.integration.workercapability.runtimeapi.RuntimeApiHttpClient;
import com.xa.mass.integration.workercapability.runtimeapi.TaskBatchApiClient;
import com.xa.mass.integration.workercapability.runtimeapi.TaskBatchApiClient.RunResult;
import com.xa.mass.integration.workercapability.acceptance.TaskBatchEvidence;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WorkerCapabilityRpcMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerCapabilityRpcMain.class.getName()
    );
    private static final List<BatchSpec> BATCHES = List.of(
            new BatchSpec(
                    "scenario-phone-number-workers",
                    "extension.worker.phonenumber.e164",
                    "rawNumber",
                    SeedKind.PHONE
            ),
            new BatchSpec(
                    "scenario-phone-number-workers",
                    "extension.worker.phonenumber.country",
                    "rawNumber",
                    SeedKind.PHONE
            ),
            new BatchSpec(
                    "scenario-phone-number-workers",
                    "extension.worker.phonenumber.original-carrier",
                    "rawNumber",
                    SeedKind.PHONE
            ),
            new BatchSpec(
                    "scenario-string-utils-workers",
                    "extension.worker.string.md5",
                    "value",
                    SeedKind.STRING
            ),
            new BatchSpec(
                    "scenario-string-utils-workers",
                    "extension.worker.string.sha1",
                    "value",
                    SeedKind.STRING
            ),
            new BatchSpec(
                    "scenario-string-utils-workers",
                    "extension.worker.string.base64.encode",
                    "value",
                    SeedKind.STRING
            )
    );

    private WorkerCapabilityRpcMain() {
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
                    "Task Batch wait must not exceed 5 minutes"
            );
        }
        RuntimeApiHttpClient runtimeApi = new RuntimeApiHttpClient(
                options.uri(
                        "server-base-url",
                        WorkerCapabilityIntegrationDefaults.SERVER_BASE_URL
                ),
                Duration.ofMillis(options.positiveLong(
                        "request-timeout-millis",
                        WorkerCapabilityIntegrationDefaults
                                .REQUEST_TIMEOUT_MILLIS
                ))
        );
        TaskBatchApiClient taskBatch = new TaskBatchApiClient(
                runtimeApi
        );
        Path proofResultDirectory = resultDirectory.resolve(proofId);
        createProofResultDirectory(
                resultDirectory,
                proofResultDirectory
        );

        Path evidenceFile = proofResultDirectory.resolve(
                "task-batch-evidence.json"
        );
        List<RunResult> runs = new ArrayList<>();
        List<Map<String, Object>> allResults = new ArrayList<>();
        try {
            List<String> phoneLines = readFirstTenLines(
                    phoneSeedPath,
                    "phone-seed.txt"
            );
            List<String> stringLines = readFirstTenLines(
                    stringSeedPath,
                    "string-seed.txt"
            );
            String phoneRemote = "phone-seed-" + proofId + ".txt";
            String stringRemote = "string-seed-" + proofId + ".txt";
            taskBatch.uploadInput(
                    phoneRemote,
                    String.join("\n", phoneLines)
            );
            taskBatch.uploadInput(
                    stringRemote,
                    String.join("\n", stringLines)
            );
            for (BatchSpec batch : BATCHES) {
                String inputFile = batch.seedKind() == SeedKind.PHONE
                        ? phoneRemote
                        : stringRemote;
                RunResult run = taskBatch.run(
                        batch.workerGroupId(),
                        batch.eventCode(),
                        batch.payloadKey(),
                        inputFile,
                        maximumWaitMillis
                );
                runs.add(run);
                requireRunSummary(
                        run,
                        batch,
                        inputFile
                );
                String output = taskBatch.downloadOutput(
                        run.outputFile()
                );
                allResults.addAll(parseJsonLines(output, run.outputFile()));
            }
            WorkerCapabilityAcceptance.Summary summary =
                    WorkerCapabilityAcceptance.verify(allResults);
            TaskBatchEvidence.writeSucceeded(
                    evidenceFile,
                    proofId,
                    runs,
                    summary
            );
        } catch (IOException | RuntimeException error) {
            writeFailureEvidence(
                    evidenceFile,
                    proofId,
                    runs,
                    allResults,
                    error
            );
            throw error;
        }
        LOG.log(
                System.Logger.Level.INFO,
                "Verified 6 Server Task Batches and 60 correlated results; "
                        + "safe evidence=" + evidenceFile
        );
    }

    private static void requireRunSummary(
            RunResult run,
            BatchSpec batch,
            String inputFile
    ) {
        if (!"succeeded".equals(run.status())
                || !batch.workerGroupId().equals(run.workerGroupId())
                || !batch.eventCode().equals(run.eventCode())
                || !batch.payloadKey().equals(run.payloadKey())
                || !inputFile.equals(run.inputFile())
                || run.inputCount() != 10
                || run.resultCount() != 10
                || run.remainingCount() != 0
                || run.loadRounds() < 1) {
            throw new IllegalStateException(
                    "Task Batch run summary is invalid for "
                            + batch.eventCode()
            );
        }
    }

    private static List<Map<String, Object>> parseJsonLines(
            String output,
            String label
    ) {
        try {
            return output.lines()
                    .map(Jsons::parseObject)
                    .toList();
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Downloaded Task Batch output is invalid: " + label,
                    error
            );
        }
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
            List<RunResult> runs,
            List<Map<String, Object>> results,
            Throwable primaryFailure
    ) {
        try {
            RuntimeException failure = primaryFailure
                    instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "Task Batch proof failed",
                            primaryFailure
                    );
            TaskBatchEvidence.writeFailed(
                    evidenceFile,
                    proofId,
                    runs,
                    results,
                    failure
            );
        } catch (IOException | RuntimeException evidenceFailure) {
            primaryFailure.addSuppressed(evidenceFailure);
        }
    }

    private enum SeedKind {
        PHONE,
        STRING
    }

    private record BatchSpec(
            String workerGroupId,
            String eventCode,
            String payloadKey,
            SeedKind seedKind
    ) {
    }
}
