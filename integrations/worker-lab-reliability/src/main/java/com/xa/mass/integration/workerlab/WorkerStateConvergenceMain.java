package com.xa.mass.integration.workerlab;

public final class WorkerStateConvergenceMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerStateConvergenceMain.class.getName()
    );

    private WorkerStateConvergenceMain() {
    }

    public static void main(String[] arguments) throws Exception {
        WorkerLabArguments parsed = WorkerLabArguments.parse(
                arguments,
                WorkerLabHarnessOptions.ARGUMENT_NAMES
        );
        WorkerLabHarnessOptions options = WorkerLabHarnessOptions.from(
                parsed,
                "worker-state-convergence"
        );
        WorkerStateConvergence.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Worker state convergence completed; summary={0}",
                options.evidenceDirectory().toAbsolutePath().normalize()
                        .resolve(ConvergenceEvidence.summaryFileName(
                                WorkerStateConvergence.LANE
                        ))
        );
    }
}
