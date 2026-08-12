package com.xa.mass.integration.workercapability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class WorkerCapabilityRpcMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerCapabilityRpcMain.class.getName()
    );

    private WorkerCapabilityRpcMain() {
    }

    public static void main(String[] arguments) throws IOException {
        CommandLineOptions options = CommandLineOptions.parse(arguments);
        String scenarioId = RuntimeApiHttpClient.identifier(
                options.string(
                        "scenario-id",
                        "worker-capability-" + System.currentTimeMillis()
                )
        );
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
        Path scenarioWorkerLabRoot = absolutePath(options.path(
                "scenario-worker-lab-root",
                "../../data/scenario-workers"
        ));
        Path scenarioResultDirectory = resultDirectory.resolve(
                scenarioId
        );
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
        long taskCloseAfterMillis = options.positiveLong(
                "task-close-after-millis",
                WorkerCapabilityIntegrationDefaults
                        .TASK_CLOSE_AFTER_MILLIS
        );
        RuntimeApiHttpClient runtimeApi = new RuntimeApiHttpClient(
                options.uri(
                        "server-base-url",
                        WorkerCapabilityIntegrationDefaults.SERVER_BASE_URL
                ),
                Duration.ofMillis(options.positiveLong(
                        "request-timeout-millis",
                        waitTimeoutMillis + 5_000
                ))
        );
        WorkerIdentityRegistrationClient identities =
                new WorkerIdentityRegistrationClient(runtimeApi);
        WorkerCapabilityTaskClient tasks =
                new WorkerCapabilityTaskClient(runtimeApi);

        createScenarioResultDirectory(
                resultDirectory,
                scenarioResultDirectory
        );
        int phoneResultCount;
        int stringResultCount;
        try {
            phoneResultCount = new PhoneNumberRpcScenario(
                    identities,
                    tasks
            ).run(
                    scenarioId,
                    phoneSeedPath,
                    scenarioResultDirectory.resolve(
                            "phone-number.jsonl"
                    ),
                    waitTimeoutMillis,
                    taskCloseAfterMillis
            );
            stringResultCount = new StringUtilityRpcScenario(
                    identities,
                    tasks
            ).run(
                    scenarioId,
                    stringSeedPath,
                    scenarioResultDirectory.resolve(
                            "string-utils.jsonl"
                    ),
                    waitTimeoutMillis,
                    taskCloseAfterMillis
            );
        } catch (IOException | RuntimeException error) {
            removeEmptyScenarioResultDirectory(
                    scenarioResultDirectory,
                    error
            );
            throw error;
        }

        WorkerCapabilityScenarioVerifier.verify(
                scenarioResultDirectory,
                scenarioWorkerLabRoot
        );

        LOG.log(
                System.Logger.Level.INFO,
                "Verified "
                        + (phoneResultCount + stringResultCount)
                        + " targeted results across 20 Workers in "
                        + scenarioResultDirectory
        );
    }

    private static Path absolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void createScenarioResultDirectory(
            Path resultDirectory,
            Path scenarioResultDirectory
    ) throws IOException {
        if (Files.exists(scenarioResultDirectory)) {
            throw new IllegalArgumentException(
                    "Scenario result directory already exists: "
                            + scenarioResultDirectory
            );
        }
        Files.createDirectories(resultDirectory);
        Files.createDirectory(scenarioResultDirectory);
    }

    private static void removeEmptyScenarioResultDirectory(
            Path scenarioResultDirectory,
            Throwable primaryFailure
    ) {
        try (var files = Files.list(scenarioResultDirectory)) {
            if (files.findAny().isEmpty()) {
                Files.delete(scenarioResultDirectory);
            }
        } catch (IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }
}
