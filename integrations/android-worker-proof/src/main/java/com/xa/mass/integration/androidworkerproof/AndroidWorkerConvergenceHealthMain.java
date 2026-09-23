package com.xa.mass.integration.androidworkerproof;

public final class AndroidWorkerConvergenceHealthMain {

    private static final System.Logger LOG = System.getLogger(
            AndroidWorkerConvergenceHealthMain.class.getName()
    );

    private AndroidWorkerConvergenceHealthMain() {
    }

    public static void main(String[] arguments) throws Exception {
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                arguments
        );
        AndroidWorkerConvergenceHealth.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Android Worker Convergence Health {0} completed; evidence={1}",
                options.phase(),
                options.evidenceFile()
        );
    }
}
