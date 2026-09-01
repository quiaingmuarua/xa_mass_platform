package com.xa.mass.integration.workerlab;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class WorkerTaskFaultConvergenceMain {

    private WorkerTaskFaultConvergenceMain() {
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
                "worker-task-fault-convergence"
        );
        Path phaseState = Path.of(parsed.required("phase-state"));
        switch (parsed.required("phase")) {
            case "arm" -> WorkerTaskFaultConvergence.arm(
                    options,
                    phaseState
            );
            case "down" -> WorkerTaskFaultConvergence.observeDown(
                    options,
                    phaseState
            );
            case "recover" -> WorkerTaskFaultConvergence.recover(
                    options,
                    phaseState
            );
            case "finality" -> WorkerTaskFaultConvergence.verifyFinality(
                    options,
                    phaseState
            );
            default -> throw new IllegalArgumentException(
                    "phase must be arm, down, recover or finality"
            );
        }
    }
}
