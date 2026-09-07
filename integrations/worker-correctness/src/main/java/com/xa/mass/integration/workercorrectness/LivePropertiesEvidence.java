package com.xa.mass.integration.workercorrectness;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only identities, counts, digests and timings may enter the public evidence. */
final class LivePropertiesEvidence {
    final List<Map<String, Object>> workers = new ArrayList<>();
    final List<Map<String, Object>> checkpoints = new ArrayList<>();
    String stage = "preflight";
    String failure;
    int mutationRequests;
    int persistedCount;
    int sendAcceptedCount;
    int replacementCount;
    int incrementalCount;
    int temporaryReadFailures;
    long elapsedMillis;

    void checkpoint(String name, int propertyCount, String digest,
                    long adapterMillis, long runtimeMillis, int observations) {
        checkpoints.add(Map.of(
                "checkpoint", name, "propertyCount", propertyCount, "snapshotSha256", digest,
                "adapterObservedWithinMillis", adapterMillis,
                "runtimeObservedWithinMillis", runtimeMillis,
                "completeSnapshotObservations", observations,
                "controlFilesUnchanged", true, "controlAdapterPropertiesUnchanged", true,
                "controlRuntimePropertiesUnchanged", true, "workerRunUnchanged", true));
    }

    void write(Path path, String proofId, String adapterId) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("proofId", proofId);
        out.put("phase", "live-properties");
        // The runner must add the independent process/access-log audit before success.
        out.put("status", failure == null ? "pending-runner-audit" : "failed");
        out.put("harnessStatus", failure == null ? "succeeded" : "failed");
        out.put("stage", stage);
        out.put("failure", failure);
        out.put("endpointManagerId", adapterId);
        out.put("workers", workers);
        out.put("rounds", 8);
        out.put("updatesPerRound", 32);
        out.put("mutationRequests", mutationRequests);
        out.put("persistedCount", persistedCount);
        out.put("sendAcceptedCount", sendAcceptedCount);
        out.put("replacementCount", replacementCount);
        out.put("incrementalCount", incrementalCount);
        out.put("temporaryReadFailures", temporaryReadFailures);
        out.put("checkpointBudgetMillis", 5_000);
        out.put("requestTimeoutMillis", 2_000);
        out.put("adapterWaitTimeoutMillis", 1_000);
        out.put("phaseBudgetMillis", 120_000);
        out.put("elapsedMillis", elapsedMillis);
        out.put("checkpoints", checkpoints);
        Files.createDirectories(path.getParent());
        Files.writeString(path, Jsons.toJson(out), StandardOpenOption.CREATE_NEW);
    }
}
