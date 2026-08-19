package com.xa.mass.integration.workerfleet;

public final class WorkerFleetAcceptanceMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerFleetAcceptanceMain.class.getName()
    );

    private WorkerFleetAcceptanceMain() {
    }

    public static void main(String[] arguments) throws Exception {
        FleetCommandLineOptions options = FleetCommandLineOptions.parse(
                arguments
        );
        FleetCommandLineOptions.Phase phase = options.requiredPhase();
        WorkerFleetAcceptance.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Worker Fleet {0} proof completed; evidence={1}",
                phase.wireValue(),
                options.evidenceFile()
        );
    }
}
