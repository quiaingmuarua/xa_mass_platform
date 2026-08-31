package com.xa.mass.integration.workerlab;

import java.util.LinkedHashSet;
import java.util.Set;

public final class WorkerConvergenceCampaignMain {

    private WorkerConvergenceCampaignMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Set<String> names = new LinkedHashSet<>(
                WorkerLabHarnessOptions.ARGUMENT_NAMES
        );
        names.add("seed");
        names.add("rounds");
        WorkerLabArguments parsed = WorkerLabArguments.parse(
                arguments,
                Set.copyOf(names)
        );
        WorkerLabHarnessOptions options = WorkerLabHarnessOptions.from(
                parsed,
                "worker-convergence-campaign"
        );
        long seed = parsed.number(
                "seed",
                WorkerConvergenceCampaign.DEFAULT_SEED
        );
        long parsedRounds = parsed.number(
                "rounds",
                WorkerConvergenceCampaign.DEFAULT_ROUNDS
        );
        if (parsedRounds < Integer.MIN_VALUE
                || parsedRounds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("rounds is out of range");
        }
        WorkerConvergenceCampaign.execute(
                options,
                seed,
                (int) parsedRounds
        );
    }
}
