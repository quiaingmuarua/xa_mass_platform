package com.xa.mass.integration.androidworkerproof;

public final class AndroidWorkerTriadCorrectnessMain {

    private AndroidWorkerTriadCorrectnessMain() {
    }

    public static void main(String[] arguments) throws Exception {
        AndroidWorkerTriadCorrectness.execute(
                AndroidWorkerProofOptions.parse(arguments)
        );
    }
}
