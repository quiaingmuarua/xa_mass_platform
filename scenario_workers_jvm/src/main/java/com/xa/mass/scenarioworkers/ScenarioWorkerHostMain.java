package com.xa.mass.scenarioworkers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Standalone process entry for the finite local Scenario Worker Lab. */
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
        ScenarioWorkers workers = ScenarioWorkers.fromJson(
                loadDefaultCapabilityAssembly(),
                options.sandboxRoot(),
                options.runtimeApiBaseUrl()
        );
        ScenarioWorkerScheduledStops scheduledStops = null;
        ScenarioWorkerControlServer controlServer = null;
        Thread shutdownHook = null;
        try {
            workers.start(options.initialWorkers());
            scheduledStops = new ScenarioWorkerScheduledStops(workers);
            controlServer = ScenarioWorkerControlServer.open(
                    options.controlPort(),
                    workers,
                    scheduledStops
            );
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
                    "SCENARIO_WORKER_LAB_READY control={0} "
                            + "initialWorkers={1}",
                    controlServer.baseUri(),
                    options.initialWorkers().name().toLowerCase(
                            java.util.Locale.ROOT
                    )
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

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignoredDuringShutdown) {
            // The registered hook owns the same idempotent close operation.
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
            ScenarioWorkers.InitialWorkers initialWorkers
    ) {

        private static final String RUNTIME_API_ARGUMENT =
                "--runtime-api-base-url";
        private static final String SANDBOX_ROOT_ARGUMENT = "--sandbox-root";
        private static final String CONTROL_PORT_ARGUMENT = "--control-port";
        private static final String INITIAL_WORKERS_ARGUMENT =
                "--initial-workers";

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
            if (initialWorkers == null) {
                throw new NullPointerException("initialWorkers");
            }
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
                        && !INITIAL_WORKERS_ARGUMENT.equals(name)) {
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
                    parseInitialWorkers(values.getOrDefault(
                            INITIAL_WORKERS_ARGUMENT,
                            "all"
                    ))
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

        private static ScenarioWorkers.InitialWorkers parseInitialWorkers(
                String value
        ) {
            return switch (value) {
                case "all" -> ScenarioWorkers.InitialWorkers.ALL;
                case "none" -> ScenarioWorkers.InitialWorkers.NONE;
                default -> throw new IllegalArgumentException(
                        "initial-workers must be all or none"
                );
            };
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
