package com.xa.mass.integration.workerscale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Coordinates one fixed proof checkpoint with the Linux process owner. */
final class ScaleProcessGate {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final ScaleOptions options;

    ScaleProcessGate(ScaleOptions options) {
        this.options = java.util.Objects.requireNonNull(options, "options");
    }

    GateResume awaitInitialHeadroom(
            int connectedAndHotWorkers,
            int qualifyingScans,
            long stableMillis
    ) {
        if (!options.stage().isInitialContraction()) {
            throw new IllegalStateException(
                    "Initial headroom gate belongs only to initial contraction"
            );
        }
        return await(
                "initial-headroom",
                options.gateDirectory().resolve("headroom-ready.json"),
                options.gateDirectory().resolve("headroom-resume.json"),
                Map.of(
                        "connectedAndHotWorkers", connectedAndHotWorkers,
                        "qualifyingScans", qualifyingScans,
                        "stableMillis", stableMillis
                )
        );
    }

    GateResume awaitServerMutation(
            int taskCount,
            int succeededItems,
            int unresolvedItems
    ) {
        if (options.stage().isInitialContraction()) {
            throw new IllegalStateException(
                    "Initial contraction does not use the Server mutation gate"
            );
        }
        return await(
                "server-mutation",
                options.gateDirectory().resolve("mutation-ready.json"),
                options.gateDirectory().resolve("resume.json"),
                Map.of(
                        "taskCount", taskCount,
                        "succeededItems", succeededItems,
                        "unresolvedItems", unresolvedItems
                )
        );
    }

    private GateResume await(
            String checkpoint,
            Path readyPath,
            Path resumePath,
            Map<String, Object> details
    ) {
        if (Files.exists(readyPath) || Files.exists(resumePath)) {
            throw new IllegalStateException(
                    "Scale process gate files must not exist before checkpoint"
            );
        }
        long readyAt = System.currentTimeMillis();
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("proofId", options.proofId());
        ready.put("stage", options.stage().wireValue());
        ready.put("checkpoint", checkpoint);
        ready.put("atEpochMillis", readyAt);
        ready.putAll(details);
        ScaleEvidence.writeExclusive(readyPath, ready);

        long started = System.nanoTime();
        long deadline = started + options.maximumConvergenceWait().toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(resumePath)) {
                Map<String, Object> resume = ScaleEvidence.readObject(
                        resumePath,
                        "scale process gate resume"
                );
                if (!resume.keySet().equals(Set.of(
                        "proofId",
                        "stage",
                        "checkpoint",
                        "resumedAtEpochMillis"
                ))) {
                    throw ScaleJson.invalid(
                            "Scale process gate resume fields changed"
                    );
                }
                if (!options.proofId().equals(
                        ScaleJson.string(resume, "proofId")
                ) || !options.stage().wireValue().equals(
                        ScaleJson.string(resume, "stage")
                ) || !checkpoint.equals(
                        ScaleJson.string(resume, "checkpoint")
                )) {
                    throw ScaleJson.invalid(
                            "Scale process gate resume identity changed"
                    );
                }
                long resumedAt = ScaleJson.integer(
                        resume,
                        "resumedAtEpochMillis"
                );
                if (resumedAt < readyAt) {
                    throw ScaleJson.invalid(
                            "Scale process gate resumed before it was ready"
                    );
                }
                return new GateResume(
                        readyAt,
                        resumedAt,
                        Duration.ofNanos(System.nanoTime() - started).toMillis()
                );
            }
            sleep();
        }
        throw new IllegalStateException(
                "Timed out waiting for scale process gate " + checkpoint
        );
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scale process gate was interrupted", error);
        }
    }

    record GateResume(
            long readyAtEpochMillis,
            long resumedAtEpochMillis,
            long waitMillis
    ) {
    }
}
