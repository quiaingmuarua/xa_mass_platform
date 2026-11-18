package com.xa.mass.integration.workercallperformance;

import java.util.LinkedHashMap;
import java.util.Set;

/** Performance lane Harness entry: one bounded phase per process, driven by the Python runner. */
public final class WorkerCallPerformanceMain {
    private static final Set<String> OPTIONS = Set.of("--phase", "--case", "--output", "--world", "--repetition",
            "--runtime-url", "--lab-url", "--experiment-config", "--experiment-profile", "--experiment-stage",
            "--recording", "--input", "--resources", "--deployment-config");

    private WorkerCallPerformanceMain() {}

    public static void main(String[] args) throws Exception {
        var options = new LinkedHashMap<String, String>();
        for (String arg : args) {
            var pair = arg.split("=", 2);
            if (pair.length != 2 || !OPTIONS.contains(pair[0]) || options.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("Invalid option");
        }
        String phase = options.get("--phase");
        if (phase == null || !options.containsKey("--output")) throw new IllegalArgumentException("phase and output required");
        if (options.containsKey("--deployment-config")
                && !Set.of("deployment-config", "case", "bootstrap", "quiesce").contains(phase))
            throw new IllegalArgumentException("Existing deployments support configuration and open-loop phases only");
        switch (phase) {
            case "deployment-config" -> DeploymentConfig.resolve(options);
            case "experiment-config" -> ExperimentConfig.resolve(options);
            case "experiment-analyze" -> CapacityEvidence.run(options);
            case "experiment-report" -> ExperimentReport.run(options);
            case "calibrate" -> LaneCalibration.run(options);
            case "case" -> {
                if (options.containsKey("--deployment-config") && LaneSaturation.handles(options.get("--case")))
                    throw new IllegalArgumentException("Existing deployments support the open-loop lane only");
                if (LaneSaturation.handles(options.get("--case"))) LaneSaturation.run(options);
                else LaneCase.run(options);
            }
            case "bootstrap", "quiesce" -> {
                if (options.containsKey("--case")) throw new IllegalArgumentException("Lane world phases take no case");
                LaneWorld.run(options);
            }
            default -> throw new IllegalArgumentException("Unknown lane phase");
        }
    }
}
