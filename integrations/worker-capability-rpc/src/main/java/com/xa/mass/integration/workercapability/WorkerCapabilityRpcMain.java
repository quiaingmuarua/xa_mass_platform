package com.xa.mass.integration.workercapability;

import com.xa.mass.integration.workercapability.cli.CommandLineOptions;
import com.xa.mass.integration.workercapability.cli.WorkerCapabilityIntegrationDefaults;
import com.xa.mass.integration.workercapability.runtimeapi.RuntimeApiHttpClient;
import com.xa.mass.integration.workercapability.runtimeapi.ScenarioRpcApiClient;
import com.xa.mass.integration.workercapability.runtimeapi.ScenarioRpcApiClient.CreateResult;
import com.xa.mass.integration.workercapability.runtimeapi.ScenarioRpcApiClient.RunResult;
import com.xa.mass.integration.workercapability.scenario.WorkerCapabilityAcceptance;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WorkerCapabilityRpcMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerCapabilityRpcMain.class.getName()
    );
    private static final List<ScenarioSpec> SCENARIOS = List.of(
            new ScenarioSpec("phonenumber.e164", SeedKind.PHONE),
            new ScenarioSpec("phonenumber.country", SeedKind.PHONE),
            new ScenarioSpec(
                    "phonenumber.original-carrier",
                    SeedKind.PHONE
            ),
            new ScenarioSpec("string.md5", SeedKind.STRING),
            new ScenarioSpec("string.sha1", SeedKind.STRING),
            new ScenarioSpec("string.base64.encode", SeedKind.STRING)
    );

    private WorkerCapabilityRpcMain() {
    }

    public static void main(String[] arguments) throws IOException {
        CommandLineOptions options = CommandLineOptions.parse(arguments);
        String proofId = RuntimeApiHttpClient.identifier(options.string(
                "scenario-id",
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
        Path scenarioWorkerLabRoot = absolutePath(options.path(
                "scenario-worker-lab-root",
                "../../data/scenario-workers"
        ));
        long loadIntervalMillis = options.positiveLong(
                "load-interval-millis",
                WorkerCapabilityIntegrationDefaults.LOAD_INTERVAL_MILLIS
        );
        int maximumLoadRounds = Math.toIntExact(options.positiveLong(
                "maximum-load-rounds",
                WorkerCapabilityIntegrationDefaults.MAXIMUM_LOAD_ROUNDS
        ));
        if (loadIntervalMillis > 300_000L
                || maximumLoadRounds > 300_000
                || loadIntervalMillis * maximumLoadRounds > 300_000L) {
            throw new IllegalArgumentException(
                    "Scenario RPC polling budget must not exceed 5 minutes"
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
        ScenarioRpcApiClient scenarioRpc = new ScenarioRpcApiClient(
                runtimeApi
        );
        Path proofResultDirectory = resultDirectory.resolve(proofId);
        createProofResultDirectory(
                resultDirectory,
                proofResultDirectory
        );

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
        List<Map<String, Object>> allResults = new ArrayList<>();
        try {
            scenarioRpc.uploadInput(
                    phoneRemote,
                    String.join("\n", phoneLines)
            );
            scenarioRpc.uploadInput(
                    stringRemote,
                    String.join("\n", stringLines)
            );
            for (ScenarioSpec scenario : SCENARIOS) {
                String inputFile = scenario.seedKind() == SeedKind.PHONE
                        ? phoneRemote
                        : stringRemote;
                CreateResult created = scenarioRpc.create(
                        scenario.scenarioType()
                );
                requireCreatedScenario(created, scenario);
                RunResult run = scenarioRpc.run(
                        created.scenarioId(),
                        inputFile,
                        loadIntervalMillis,
                        maximumLoadRounds
                );
                requireRunSummary(
                        run,
                        created.scenarioId(),
                        scenario,
                        inputFile
                );
                String output = scenarioRpc.downloadOutput(
                        run.outputFile()
                );
                Files.writeString(
                        proofResultDirectory.resolve(run.outputFile()),
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                allResults.addAll(parseJsonLines(output, run.outputFile()));
            }
        } catch (IOException | RuntimeException error) {
            removeEmptyProofResultDirectory(
                    proofResultDirectory,
                    error
            );
            throw error;
        }

        WorkerCapabilityAcceptance.verify(
                allResults,
                scenarioWorkerLabRoot
        );
        LOG.log(
                System.Logger.Level.INFO,
                "Verified 6 Server Scenario RPC outputs, 60 results, and "
                        + "20 persistent Worker identities in "
                        + proofResultDirectory
        );
    }

    private static void requireRunSummary(
            RunResult run,
            String scenarioId,
            ScenarioSpec scenario,
            String inputFile
    ) {
        if (!scenarioId.equals(run.scenarioId())
                || !"succeeded".equals(run.status())
                || !inputFile.equals(run.inputFile())
                || run.inputCount() != 10
                || run.resultCount() != 10
                || run.remainingCount() != 0
                || run.loadRounds() < 1) {
            throw new IllegalStateException(
                    "Scenario RPC run summary is invalid for "
                            + scenario.scenarioType()
            );
        }
    }

    private static void requireCreatedScenario(
            CreateResult created,
            ScenarioSpec scenario
    ) {
        if (!scenario.scenarioType().equals(created.scenarioType())
                || !"created".equals(created.status())) {
            throw new IllegalStateException(
                    "Scenario RPC create summary is invalid for "
                            + scenario.scenarioType()
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
                    "Downloaded Scenario RPC output is invalid: " + label,
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

    private static void removeEmptyProofResultDirectory(
            Path proofResultDirectory,
            Throwable primaryFailure
    ) {
        try (var files = Files.list(proofResultDirectory)) {
            if (files.findAny().isEmpty()) {
                Files.delete(proofResultDirectory);
            }
        } catch (IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private enum SeedKind {
        PHONE,
        STRING
    }

    private record ScenarioSpec(String scenarioType, SeedKind seedKind) {
    }
}
