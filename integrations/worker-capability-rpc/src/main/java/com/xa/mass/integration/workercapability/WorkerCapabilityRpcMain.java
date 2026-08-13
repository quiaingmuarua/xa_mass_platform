package com.xa.mass.integration.workercapability;

import com.xa.mass.integration.workercapability.cli.CommandLineOptions;
import com.xa.mass.integration.workercapability.cli.WorkerCapabilityIntegrationDefaults;
import com.xa.mass.integration.workercapability.process.RpcResult;
import com.xa.mass.integration.workercapability.runtimeapi.RuntimeApiHttpClient;
import com.xa.mass.integration.workercapability.runtimeapi.WorkerGroupRpcClient;
import com.xa.mass.integration.workercapability.scenario.PhoneNumberProcess;
import com.xa.mass.integration.workercapability.scenario.StringUtilityProcess;
import com.xa.mass.integration.workercapability.scenario.WorkerCapabilityAcceptance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        WorkerGroupRpcClient rpc = new WorkerGroupRpcClient(runtimeApi);

        createScenarioResultDirectory(
                resultDirectory,
                scenarioResultDirectory
        );
        List<RpcResult> phoneResults;
        List<RpcResult> stringResults;
        try {
            phoneResults = PhoneNumberProcess.create(
                    rpc,
                    scenarioId,
                    readFirstTenLines(phoneSeedPath, "phone-seed.txt"),
                    scenarioResultDirectory.resolve("phone-number.jsonl"),
                    waitTimeoutMillis
            ).start();
            stringResults = StringUtilityProcess.create(
                    rpc,
                    scenarioId,
                    readFirstTenLines(stringSeedPath, "string-seed.txt"),
                    scenarioResultDirectory.resolve("string-utils.jsonl"),
                    waitTimeoutMillis
            ).start();
        } catch (IOException | RuntimeException error) {
            removeEmptyScenarioResultDirectory(
                    scenarioResultDirectory,
                    error
            );
            throw error;
        }

        WorkerCapabilityAcceptance.verify(
                phoneResults,
                stringResults,
                scenarioWorkerLabRoot
        );

        LOG.log(
                System.Logger.Level.INFO,
                "Verified "
                        + (phoneResults.size() + stringResults.size())
                        + " WorkerGroup RPC results and 20 persistent "
                        + "Worker identities in "
                        + scenarioResultDirectory
        );
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
