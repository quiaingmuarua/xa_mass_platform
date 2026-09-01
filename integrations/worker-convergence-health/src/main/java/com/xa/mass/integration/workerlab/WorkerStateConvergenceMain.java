package com.xa.mass.integration.workerlab;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class WorkerStateConvergenceMain {

    private WorkerStateConvergenceMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Set<String> names = new LinkedHashSet<>(
                WorkerLabHarnessOptions.ARGUMENT_NAMES
        );
        names.add("phase");
        names.add("phase-state");
        WorkerLabArguments parsed = WorkerLabArguments.parse(
                arguments,
                Set.copyOf(names)
        );
        WorkerLabHarnessOptions options = WorkerLabHarnessOptions.from(
                parsed,
                "worker-state-server-convergence"
        );
        Path phaseState = Path.of(parsed.required("phase-state"));
        switch (parsed.required("phase")) {
            case "before-server-restart" ->
                    WorkerStateConvergence.beforeServerRestart(
                            options,
                            phaseState
                    );
            case "after-server-restart" ->
                    WorkerStateConvergence.afterServerRestart(
                            options,
                            phaseState
                    );
            default -> throw new IllegalArgumentException(
                    "phase must be before-server-restart or after-server-restart"
            );
        }
    }
}
