package com.xa.mass.integration.androidworkerproof;

public final class AndroidWorkerCorrectnessMain {

    private static final System.Logger LOG = System.getLogger(
            AndroidWorkerCorrectnessMain.class.getName()
    );

    private AndroidWorkerCorrectnessMain() {
    }

    public static void main(String[] arguments) throws Exception {
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                arguments
        );
        AndroidWorkerCorrectness.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Android Worker Correctness {0} completed; evidence={1}",
                options.phase(),
                options.evidenceFile()
        );
    }
}
