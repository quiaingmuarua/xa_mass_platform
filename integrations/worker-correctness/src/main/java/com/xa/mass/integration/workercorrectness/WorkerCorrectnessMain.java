package com.xa.mass.integration.workercorrectness;

public final class WorkerCorrectnessMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerCorrectnessMain.class.getName()
    );

    private WorkerCorrectnessMain() {
    }

    public static void main(String[] arguments) throws Exception {
        CorrectnessOptions options = CorrectnessOptions.parse(
                arguments
        );
        CorrectnessOptions.Phase phase = options.requiredPhase();
        WorkerCorrectness.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Worker Correctness {0} proof completed; evidence={1}",
                phase.wireValue(),
                options.evidenceFile()
        );
    }
}
