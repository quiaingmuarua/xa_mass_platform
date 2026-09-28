package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Offline per-case scheduling attribution from the Server's default-off Dispatch Owner events.
 * Only aggregate stage events (empty sampling key) inside each case window are used; raw JFR,
 * identities and payloads stay private.
 */
public final class LaneAttribution {
    static final String EVENT = "xa.mass.TaskDispatch";
    static final long MAX_RECORDING_BYTES = 256L * 1024 * 1024;

    record Window(String name, long startEpochMillis, long endEpochMillis) {}

    /** Per-window accumulation of the stages the lane attributes. */
    static final class Totals {
        final List<Long> roundNanos = new ArrayList<>();
        long checkedItems, claimable, assigned, confirmAttempts, confirmRejected, refillRounds, refillAdded;
        long candidateized, candidateizeRequested;

        void add(String stage, int batchSize, int count, long elapsedNanos) {
            switch (stage) {
                case "DISPATCH_ROUND" -> roundNanos.add(elapsedNanos);
                case "DISPATCH_CHECK" -> checkedItems += batchSize;
                case "CANDIDATES" -> { claimable += batchSize; assigned += count; }
                case "WORKER_CONFIRM" -> confirmAttempts += batchSize;
                case "WORKER_CONFIRM_REJECTED" -> confirmRejected += count;
                case "REFILL_ROUND" -> { refillRounds++; refillAdded += count; }
                case "CANDIDATEIZE" -> { candidateizeRequested += batchSize; candidateized += count; }
                default -> { }
            }
        }

        Map<String, Object> summary(double seconds) {
            var result = new LinkedHashMap<String, Object>();
            result.put("dispatchRounds", roundNanos.size());
            result.put("dispatchRoundsPerSecond", roundNanos.size() / seconds);
            result.put("dispatchRoundMillis", Map.of("p50", percentileMillis(roundNanos, .50), "p99", percentileMillis(roundNanos, .99)));
            result.put("checkedItemsPerSecond", checkedItems / seconds);
            result.put("claimableItems", claimable);
            result.put("assignedItems", assigned);
            // Claimable Items that received no candidate in their round; a cost signal, not a failure.
            result.put("candidateShortfallRatio", claimable == 0 ? 0.0 : (claimable - assigned) / (double) claimable);
            result.put("strictAcquisitionAttempts", confirmAttempts);
            result.put("strictAcquisitionStaleRatio", confirmAttempts == 0 ? 0.0 : confirmRejected / (double) confirmAttempts);
            result.put("refillRounds", refillRounds);
            result.put("refillAdmitted", refillAdded);
            result.put("candidateizeRatio", candidateizeRequested == 0 ? 0.0 : candidateized / (double) candidateizeRequested);
            return result;
        }
    }

    private LaneAttribution() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("recording windows output required");
        Path source = Path.of(args[0]);
        if (Files.size(source) > MAX_RECORDING_BYTES) throw new IllegalArgumentException("Recording above fixed bound");
        var windows = new ArrayList<Window>();
        for (var row : Jsons.parseArray(Files.readString(Path.of(args[1])))) {
            var window = CallApi.object(row);
            windows.add(new Window(CallApi.string(window, "name"), ((Number) window.get("startEpochMillis")).longValue(),
                    ((Number) window.get("endEpochMillis")).longValue()));
        }
        Files.writeString(Path.of(args[2]), Jsons.toJson(summarize(source, windows)), StandardOpenOption.CREATE_NEW);
    }

    static Map<String, Object> summarize(Path source, List<Window> windows) throws Exception {
        var totals = new LinkedHashMap<String, Totals>();
        windows.forEach(window -> totals.put(window.name(), new Totals()));
        boolean dataLoss = false;
        long events = 0;
        try (var recording = new RecordingFile(source)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.DataLoss")) { dataLoss = true; continue; }
                if (!type.equals(EVENT) || !"".equals(event.getString("key"))) continue;
                events++;
                long at = event.getStartTime().toEpochMilli();
                for (var window : windows) {
                    if (at >= window.startEpochMillis() && at < window.endEpochMillis())
                        totals.get(window.name()).add(event.getString("stage"), event.getInt("batchSize"),
                                event.getInt("count"), event.getLong("elapsedNanos"));
                }
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("complete", !dataLoss && events > 0);
        result.put("dataLoss", dataLoss);
        result.put("dispatchEvents", events);
        var cases = new LinkedHashMap<String, Object>();
        for (var window : windows)
            cases.put(window.name(), totals.get(window.name())
                    .summary(Math.max(1, window.endEpochMillis() - window.startEpochMillis()) / 1000.0));
        result.put("cases", cases);
        return result;
    }

    static double percentileMillis(List<Long> nanos, double fraction) {
        return CallLoad.percentile(nanos, fraction) / 1e6;
    }
}
