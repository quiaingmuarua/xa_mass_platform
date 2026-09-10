package com.xa.mass.scenarioworkers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Standalone process entry for the finite Lab and SMS Worker scenarios. */
public final class ScenarioWorkerHostMain {

    static final URI DEFAULT_RUNTIME_API_BASE_URL =
            URI.create("http://127.0.0.1:18082");
    static final String DEFAULT_SANDBOX_ROOT = "data/scenario-workers";
    static final int DEFAULT_CONTROL_PORT =
            ScenarioWorkerControlServer.DEFAULT_PORT;
    static final String DEFAULT_CAPABILITY_ASSEMBLY_RESOURCE =
            "/com/xa/mass/scenarioworkers/"
                    + "default-capability-assembly.json";

    private static final System.Logger LOGGER = System.getLogger(
            ScenarioWorkerHostMain.class.getName()
    );

    private ScenarioWorkerHostMain() {
    }

    public static void main(String[] arguments) throws Exception {
        HostOptions options = HostOptions.parse(arguments);
        ScenarioWorkerStartupPlan startupPlan = options.startupPlanPath()
                == null
                ? ScenarioWorkerStartupPlan.defaults()
                : ScenarioWorkerStartupPlan.load(options.startupPlanPath());
        ScenarioWorkers workers = !options.scenario().equals("lab")
                ? ScenarioWorkers.products(options.runtimeApiBaseUrl(), options.smsCounts(), options.scenario()) : ScenarioWorkers.fromJson(
                loadCapabilityAssembly(options.capabilityAssemblyPath()),
                options.sandboxRoot(),
                options.runtimeApiBaseUrl()
        );
        ScenarioWorkerScheduledStops scheduledStops = null;
        ScenarioWorkerControlServer controlServer = null;
        Thread shutdownHook = null;
        try {
            if (!workers.isSms()) scheduledStops = new ScenarioWorkerScheduledStops(workers);
            controlServer = ScenarioWorkerControlServer.open(
                    options.controlPort(),
                    workers,
                    scheduledStops
            );
            workers.start(startupPlan);
            scheduleStartupStops(startupPlan, scheduledStops);
            ScenarioWorkerScheduledStops finalScheduledStops =
                    scheduledStops;
            ScenarioWorkerControlServer finalControlServer = controlServer;
            shutdownHook = new Thread(
                    () -> closeHost(
                            finalControlServer,
                            finalScheduledStops,
                            workers
                    ),
                    "scenario-worker-host-shutdown"
            );
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            controlServer.start();
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "SCENARIO_WORKER_LAB_READY control={0}/lab "
                            + "initialWorkerCount={1} scheduledStopCount={2} scenario={3}",
                    controlServer.baseUri(),
                    workers.initialWorkerCount(),
                    startupPlan.scheduledStops().size(),
                    options.scenario()
            );
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (shutdownHook != null) {
                removeShutdownHook(shutdownHook);
            }
            closeHost(controlServer, scheduledStops, workers);
        }
    }

    static String loadDefaultCapabilityAssembly() {
        try (InputStream input = ScenarioWorkerHostMain.class
                .getResourceAsStream(DEFAULT_CAPABILITY_ASSEMBLY_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing " + DEFAULT_CAPABILITY_ASSEMBLY_RESOURCE
                );
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not load default Scenario capability assembly",
                    error
            );
        }
    }

    static String loadCapabilityAssembly(String configuredPath) {
        if (configuredPath == null) {
            return loadDefaultCapabilityAssembly();
        }
        Path path;
        try {
            path = Path.of(configuredPath).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "capability-assembly must be a valid path",
                    error
            );
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "capability-assembly must identify a regular file"
            );
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not load Scenario capability assembly " + path,
                    error
            );
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignoredDuringShutdown) {
            // The registered hook owns the same idempotent close operation.
        }
    }

    private static void scheduleStartupStops(
            ScenarioWorkerStartupPlan startupPlan,
            ScenarioWorkerScheduledStops scheduledStops
    ) {
        for (ScenarioWorkerStartupPlan.ScheduledStop stop
                : startupPlan.scheduledStops()) {
            ScenarioWorkerCoordinate worker = stop.worker();
            if (!scheduledStops.schedule(
                    worker.workerGroupId(),
                    worker.labWorkerKey(),
                    stop.delayMillis()
            )) {
                throw new IllegalStateException(
                        "Duplicate startup scheduled stop for "
                                + worker.workerGroupId()
                                + "/"
                                + worker.labWorkerKey()
                );
            }
        }
    }

    private static void closeHost(
            ScenarioWorkerControlServer controlServer,
            ScenarioWorkerScheduledStops scheduledStops,
            ScenarioWorkers workers
    ) {
        RuntimeException failure = null;
        if (controlServer != null) {
            try {
                controlServer.close();
            } catch (RuntimeException error) {
                failure = error;
            }
        }
        if (scheduledStops != null) {
            try {
                scheduledStops.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        try {
            workers.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        if (controlServer != null) controlServer.awaitClosed();
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    record HostOptions(
            URI runtimeApiBaseUrl,
            String sandboxRoot,
            int controlPort,
            String startupPlanPath,
            String capabilityAssemblyPath,
            String scenario,
            int[] smsCounts
    ) {

        private static final String RUNTIME_API_ARGUMENT =
                "--runtime-api-base-url";
        private static final String SANDBOX_ROOT_ARGUMENT = "--sandbox-root";
        private static final String CONTROL_PORT_ARGUMENT = "--control-port";
        private static final String STARTUP_PLAN_ARGUMENT = "--startup-plan";
        private static final String CAPABILITY_ASSEMBLY_ARGUMENT =
                "--capability-assembly";
        private static final String SCENARIO_ARGUMENT = "--scenario";
        private static final String SMS_COUNTS_ARGUMENT = "--sms-counts";
        private static final String DEVICE_COUNTS_ARGUMENT = "--device-counts";

        HostOptions {
            requireRuntimeApiBaseUrl(runtimeApiBaseUrl);
            if (sandboxRoot == null || sandboxRoot.isBlank()) {
                throw new IllegalArgumentException(
                        "sandbox-root must be non-blank"
                );
            }
            if (controlPort < 0 || controlPort > 65_535) {
                throw new IllegalArgumentException(
                        "control-port must be between 0 and 65535"
                );
            }
            if (startupPlanPath != null && startupPlanPath.isBlank()) {
                throw new IllegalArgumentException(
                        "startup-plan must be non-blank"
                );
            }
            if (capabilityAssemblyPath != null
                    && capabilityAssemblyPath.isBlank()) {
                throw new IllegalArgumentException(
                        "capability-assembly must be non-blank"
                );
            }
            if (!java.util.Set.of("lab", "sms", "messages", "products").contains(scenario))
                throw new IllegalArgumentException("scenario must be lab, sms, messages or products");
            smsCounts = smsCounts.clone();
        }

        static HostOptions parse(String[] arguments) {
            if (arguments == null) {
                throw new IllegalArgumentException("arguments must be present");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : arguments) {
                if (argument == null) {
                    throw new IllegalArgumentException(
                            "Scenario Worker Host argument must be non-null"
                    );
                }
                int separator = argument.indexOf('=');
                if (separator <= 2 || separator == argument.length() - 1) {
                    throw new IllegalArgumentException(
                            "Scenario Worker Host arguments must use --name=value"
                    );
                }
                String name = argument.substring(0, separator);
                String value = argument.substring(separator + 1);
                if (!RUNTIME_API_ARGUMENT.equals(name)
                        && !SANDBOX_ROOT_ARGUMENT.equals(name)
                        && !CONTROL_PORT_ARGUMENT.equals(name)
                        && !STARTUP_PLAN_ARGUMENT.equals(name)
                        && !CAPABILITY_ASSEMBLY_ARGUMENT.equals(name)
                        && !SCENARIO_ARGUMENT.equals(name)
                        && !SMS_COUNTS_ARGUMENT.equals(name) && !DEVICE_COUNTS_ARGUMENT.equals(name)) {
                    throw new IllegalArgumentException(
                            "Unknown Scenario Worker Host argument: " + name
                    );
                }
                if (values.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Scenario Worker Host argument: " + name
                    );
                }
            }
            String scenario = values.getOrDefault(SCENARIO_ARGUMENT, "lab");
            if (!"lab".equals(scenario) && (values.containsKey(SANDBOX_ROOT_ARGUMENT)
                    || values.containsKey(STARTUP_PLAN_ARGUMENT) || values.containsKey(CAPABILITY_ASSEMBLY_ARGUMENT)))
                throw new IllegalArgumentException("SMS does not accept Lab inventory, capability assembly or startup plan");
            if (!"sms".equals(scenario) && values.containsKey(SMS_COUNTS_ARGUMENT))
                throw new IllegalArgumentException("sms-counts requires scenario=sms");
            if (values.containsKey(DEVICE_COUNTS_ARGUMENT) && !java.util.Set.of("messages", "products").contains(scenario))
                throw new IllegalArgumentException("device-counts requires scenario=messages or products");
            int[] counts;
            try {
                counts = java.util.Arrays.stream(values.getOrDefault(scenario.equals("sms") ? SMS_COUNTS_ARGUMENT : DEVICE_COUNTS_ARGUMENT, "20,20,20").split(",", -1))
                        .mapToInt(Integer::parseInt).toArray();
            } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid sms-counts", error); }
            if (counts.length != 3 || java.util.Arrays.stream(counts).anyMatch(n -> n < 1)
                    || java.util.Arrays.stream(counts).asLongStream().sum() > 10_000)
                throw new IllegalArgumentException("sms-counts requires three positive counts, total <= 10000");
            return new HostOptions(
                    URI.create(values.getOrDefault(
                            RUNTIME_API_ARGUMENT,
                            DEFAULT_RUNTIME_API_BASE_URL.toString()
                    )),
                    values.getOrDefault(
                            SANDBOX_ROOT_ARGUMENT,
                            DEFAULT_SANDBOX_ROOT
                    ),
                    parseControlPort(values.getOrDefault(
                            CONTROL_PORT_ARGUMENT,
                            Integer.toString(DEFAULT_CONTROL_PORT)
                    )),
                    values.get(STARTUP_PLAN_ARGUMENT),
                    values.get(CAPABILITY_ASSEMBLY_ARGUMENT),
                    scenario,
                    counts
            );
        }

        private static int parseControlPort(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "control-port must be an integer",
                        error
                );
            }
        }

        private static void requireRuntimeApiBaseUrl(URI value) {
            if (value == null
                    || !value.isAbsolute()
                    || value.getHost() == null
                    || value.getQuery() != null
                    || value.getFragment() != null
                    || (!("http".equalsIgnoreCase(value.getScheme()))
                    && !("https".equalsIgnoreCase(value.getScheme())))) {
                throw new IllegalArgumentException(
                        "runtime-api-base-url must be an absolute HTTP(S) URI"
                );
            }
        }
    }
}
