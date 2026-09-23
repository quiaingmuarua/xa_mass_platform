package com.xa.mass.integration.androidworkerproof;

public final class AndroidWorkerTriadConvergenceHealthMain {

    private AndroidWorkerTriadConvergenceHealthMain() {
    }

    public static void main(String[] arguments) throws Exception {
        AndroidWorkerTriadConvergenceHealth.execute(
                AndroidWorkerProofOptions.parse(arguments)
        );
    }
}
