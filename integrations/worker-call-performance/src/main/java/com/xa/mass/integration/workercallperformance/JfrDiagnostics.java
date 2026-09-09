package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Offline bounded whitelist reader. Raw JFR, environment, identities and payloads stay private. */
public final class JfrDiagnostics {
    private static final Set<String> EVENTS = Set.of("jdk.CPULoad", "jdk.ExecutionSample", "jdk.NativeMethodSample",
            "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.Compilation", "jdk.JavaMonitorEnter", "jdk.ThreadPark",
            "jdk.SocketRead", "jdk.SocketWrite", "jdk.ObjectAllocationSample", "jdk.VirtualThreadPinned", "jdk.DataLoss",
            "xa.mass.ServerDeliveryStage", "xa.mass.AdapterRemote", "xa.mass.ReportQueue", "xa.mass.HttpInitial",
            "xa.mass.HttpCompletion", "xa.mass.HttpExecutor", "xa.mass.TaskSubmission", "xa.mass.TaskStorage",
            "xa.mass.TaskDispatch", "xa.mass.TaskResult", "xa.mass.TaskRpc");
    private static final Set<String> TAGS = Set.of("DIRECT_BINDING", "DIRECT_OFFER", "COMMAND_CONSUME", "REPORT_APPEND",
            "DIRECT_CALL", "TASK", "SERVER", "SYSTEM", "KERNEL", "ADMITTED", "INGRESS_DROP", "DRAINED", "REQUEUED",
            "SUBMISSION_DROP", "SHUTDOWN_DROP", "asynchronous", "synchronous", "timeout", "error", "handler_error",
            "ITEMS_CALL", "ACTIVATE_BEFORE", "ACTIVATE_AFTER", "ITEM_STORED", "ITEM_INITIALIZED", "SUBMISSION",
            "IMMEDIATE_PROBE", "OBSERVED", "RESULT_PROBE", "DISPATCH_ROUND", "DISPATCH_DEFER", "DISPATCH_CHECK",
            "FAILED_RESULT_STORED", "CANDIDATES", "WORKER_CONFIRM", "ITEM_CLAIM", "CLAIMED", "COMMAND_APPEND",
            "COMMAND_PUBLISHED", "WAIT_CLOSED", "WAIT_REJECTED", "WAIT_ADMITTED", "WAIT_TIMEOUT", "WAIT_SHUTDOWN",
            "WAIT_CANCELLED", "PROBE_LATENESS", "RESULT_DECODE", "RESULT_PROCESS", "RESULT_STORED", "WORKER_RELEASE",
            "RESULT_FAILURE_DECODE", "TASK_RESULT_CONSUME");
    private static final List<String> STACK_PREFIXES = List.of("com.xa.mass.", "java.", "jdk.", "sun.",
            "org.springframework.", "org.apache.", "io.lettuce.", "io.netty.", "tools.jackson.", "com.fasterxml.", "okhttp3.", "okio.");
    private static final int MAX_GROUPS = 4096;
    private static final int MAX_STACKS = 8192;

    private JfrDiagnostics() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 6) throw new IllegalArgumentException("recording output startedEpochMillis seconds role [callPath] required");
        Path source = Path.of(args[0]);
        if (Files.size(source) > 256L * 1024 * 1024) throw new IllegalArgumentException("Recording above fixed bound");
        var result = summarize(source, Long.parseLong(args[2]), Integer.parseInt(args[3]), args[4], args.length == 6 ? args[5] : "DIRECT_CALL");
        Files.writeString(Path.of(args[1]), Jsons.toJson(result), StandardOpenOption.CREATE_NEW);
    }

    static Map<String, Object> summarize(Path source, long started, int seconds, String role) throws Exception {
        return summarize(source, started, seconds, role, "DIRECT_CALL");
    }

    static Map<String, Object> summarize(Path source, long started, int seconds, String role, String callPath) throws Exception {
        if (!Set.of("server", "host", "harness").contains(role) || seconds < 1 || seconds > 120)
            throw new IllegalArgumentException("Unknown role or unbounded interval");
        var groups = new LinkedHashMap<String, Aggregate>();
        var stacks = new LinkedHashMap<String, Long>();
        var coverage = new ArrayList<Long>();
        var executorCoverage = new ArrayList<Long>();
        var measuredEvents = new TreeMap<String, Long>();
        var traces = new TaskTraceSummary();
        long droppedStackSamples = 0, dataLoss = 0;
        boolean overflow = false;
        long earliest = Long.MAX_VALUE, latest = Long.MIN_VALUE;
        try (var recording = new RecordingFile(source)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!EVENTS.contains(type)) continue;
                long timestamp = event.getStartTime().toEpochMilli();
                earliest = Math.min(earliest, timestamp);
                latest = Math.max(latest, event.getEndTime().toEpochMilli());
                if (type.equals("jdk.DataLoss")) { dataLoss += event.getLong("amount"); continue; }
                if (type.equals("jdk.CPULoad") && timestamp >= started - 3000 && timestamp <= started + seconds * 1000L + 3000)
                    coverage.add(timestamp);
                if (type.equals("xa.mass.HttpExecutor") && timestamp >= started - 3000 && timestamp <= started + seconds * 1000L + 3000)
                    executorCoverage.add(timestamp);
                long elapsed = event.hasField("elapsedNanos") ? event.getLong("elapsedNanos") : event.getDuration().toNanos();
                // Servlet elapsed events are committed at the end; assign their start to the diagnostic bucket.
                long activityStarted = event.hasField("elapsedNanos") ? timestamp - elapsed / 1_000_000 : timestamp;
                if (event.hasField("key") && !event.getString("key").isEmpty()) {
                    if (role.equals("server") && event.hasField("startedNanos")) {
                        String stage = event.getString("stage");
                        traces.add(event.getString("key"), new TaskTraceSummary.Point(TAGS.contains(stage) ? stage : "UNRECOGNIZED",
                                event.getLong("startedNanos"), event.getLong("endedNanos"), activityStarted, event.getBoolean("failed")));
                    }
                    continue; // Sampled copies never inflate aggregate operation counts or durations.
                }
                long offset = activityStarted - started;
                if (offset < -20_000 || offset >= seconds * 1000L) continue;
                if (offset >= 0) measuredEvents.merge(type, 1L, Long::sum);
                long bucket = Math.floorDiv(offset, 5000) * 5;
                String category = category(event);
                String key = bucket + ":" + category;
                Aggregate aggregate = groups.get(key);
                if (aggregate == null && groups.size() == MAX_GROUPS) { overflow = true; continue; }
                if (aggregate == null) { aggregate = new Aggregate(bucket, category); groups.put(key, aggregate); }
                aggregate.add(event, elapsed);
                String stack = safeStack(event);
                if (stack != null) {
                    String stackKey = (offset < 0 ? "warmup" : offset < 30_000 ? "surge" : "sustained") + ":" + type + ":" + stack;
                    if (stacks.containsKey(stackKey) || stacks.size() < MAX_STACKS) stacks.merge(stackKey, 1L, Long::sum);
                    else droppedStackSamples++;
                }
            }
        }
        coverage.sort(Long::compare);
        long maxGap = 0;
        for (int i = 1; i < coverage.size(); i++) maxGap = Math.max(maxGap, coverage.get(i) - coverage.get(i - 1));
        boolean covered = !coverage.isEmpty() && coverage.getFirst() <= started + 2000
                && coverage.getLast() >= started + seconds * 1000L - 2000 && maxGap <= 3000;
        executorCoverage.sort(Long::compare);
        boolean ownerCovered = !role.equals("server") || (
                Set.of("xa.mass.HttpInitial", "xa.mass.HttpCompletion", "xa.mass.ServerDeliveryStage",
                        "xa.mass.AdapterRemote", "xa.mass.ReportQueue").stream().allMatch(measuredEvents::containsKey)
                && covers(executorCoverage, started, seconds));
        if (role.equals("server") && callPath.equals("TASK")) ownerCovered = ownerCovered
                && Set.of("xa.mass.TaskStorage", "xa.mass.TaskSubmission", "xa.mass.TaskDispatch", "xa.mass.TaskResult", "xa.mass.TaskRpc")
                        .stream().allMatch(measuredEvents::containsKey);
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1);
        result.put("role", role);
        result.put("complete", covered && ownerCovered && dataLoss == 0 && !overflow && droppedStackSamples == 0);
        result.put("measurementStartedEpochMillis", started);
        result.put("measurementSeconds", seconds);
        result.put("firstRecordedEpochMillis", earliest == Long.MAX_VALUE ? null : earliest);
        result.put("lastRecordedEpochMillis", latest == Long.MIN_VALUE ? null : latest);
        result.put("cpuCoverageSamples", coverage.size());
        result.put("maximumCpuSampleGapMillis", maxGap);
        result.put("ownerObservationCoverage", role.equals("server") ? ownerCovered : null);
        result.put("measurementEventCounts", measuredEvents);
        result.put("dataLossBytes", dataLoss);
        result.put("aggregateOverflow", overflow);
        result.put("discardedStackSamples", droppedStackSamples);
        result.put("stackMeaning", "Retained whitelist stacks, grouped by phase and event; counts are samples, not elapsed or CPU time. Omitted frames are marked; bounded overflow stays explicit.");
        result.put("latencyMeaning", "Duration percentiles are power-of-two histogram upper bounds; exact call percentiles belong to Harness samples.");
        result.put("buckets", groups.values().stream().map(Aggregate::summary).toList());
        result.put("stacks", stacks);
        if (role.equals("server") && callPath.equals("TASK")) result.put("taskTrace", traces.summarize(started, seconds));
        return result;
    }

    private static boolean covers(List<Long> timestamps, long started, int seconds) {
        if (timestamps.isEmpty() || timestamps.getFirst() > started + 2000
                || timestamps.getLast() < started + seconds * 1000L - 2000) return false;
        for (int i = 1; i < timestamps.size(); i++) if (timestamps.get(i) - timestamps.get(i - 1) > 3000) return false;
        return true;
    }

    private static String category(RecordedEvent event) {
        var name = new StringBuilder(event.getEventType().getName());
        for (String field : List.of("stage", "operation", "destination", "action", "reason")) {
            if (event.hasField(field)) {
                String value = event.getString(field);
                name.append('/').append(value != null && TAGS.contains(value) ? value : "UNRECOGNIZED");
            }
        }
        return name.toString();
    }

    private static String safeStack(RecordedEvent event) {
        var trace = event.getStackTrace();
        if (trace == null || trace.getFrames().isEmpty()) return null;
        var frames = new ArrayList<String>();
        for (var frame : trace.getFrames()) {
            var method = frame.getMethod();
            if (method == null) continue;
            String type = method.getType().getName();
            if (STACK_PREFIXES.stream().anyMatch(type::startsWith)) {
                frames.add(type.replaceAll("[/.]0x[0-9a-fA-F]+", "") + "." + method.getName());
            } else frames.add("[omitted]");
            if (frames.size() == 8) break;
        }
        return String.join(" <- ", frames);
    }

    private static final class Aggregate {
        private final long offset;
        private final String category;
        private long count, duration, maxDuration;
        private final long[] histogram = new long[64];
        private final Map<String, Long> sums = new TreeMap<>();
        private final Map<String, Long> maxima = new TreeMap<>();
        private double cpuSum;
        private long cpuSamples;

        private Aggregate(long offset, String category) { this.offset = offset; this.category = category; }

        void add(RecordedEvent event, long nanos) {
            count++;
            if (event.hasField("batchSize") && event.getInt("batchSize") == 100) sums.merge("batchesOf100", 1L, Long::sum);
            duration += nanos;
            maxDuration = Math.max(maxDuration, nanos);
            histogram[nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1)]++;
            for (String field : List.of("batchSize", "offered", "occupied", "count", "bytesRead", "bytesWritten", "weight"))
                if (event.hasField(field)) sums.merge(field, ((Number) event.getValue(field)).longValue(), Long::sum);
            for (String field : List.of("depth", "poolSize", "active", "queued", "maximum", "minimum"))
                if (event.hasField(field)) maxima.merge(field, ((Number) event.getValue(field)).longValue(), Math::max);
            for (String field : List.of("failed", "virtualThread", "initialVirtualThread", "platformPool"))
                if (event.hasField(field)) sums.merge(field + (event.getBoolean(field) ? "True" : "False"), 1L, Long::sum);
            if (event.hasField("httpStatus")) sums.merge("http" + event.getInt("httpStatus"), 1L, Long::sum);
            if (event.getEventType().getName().equals("jdk.CPULoad")) {
                cpuSum += event.getFloat("jvmUser") + event.getFloat("jvmSystem");
                cpuSamples++;
            }
        }

        Map<String, Object> summary() {
            var result = new LinkedHashMap<String, Object>();
            result.put("fromSeconds", offset);
            result.put("event", category);
            result.put("samples", count);
            result.put("totalDurationMillis", duration / 1e6);
            result.put("meanDurationMillis", count == 0 ? null : duration / 1e6 / count);
            result.put("maximumDurationMillis", maxDuration / 1e6);
            result.put("p50UpperBoundMillis", percentile(.50));
            result.put("p95UpperBoundMillis", percentile(.95));
            result.put("p99UpperBoundMillis", percentile(.99));
            result.put("sums", sums);
            var applicableMaxima = new TreeMap<String, Object>();
            maxima.forEach((key, value) -> applicableMaxima.put(key, value < 0 ? null : value));
            result.put("maxima", applicableMaxima);
            if (cpuSamples > 0) result.put("meanJvmCpuFraction", cpuSum / cpuSamples);
            return result;
        }

        double percentile(double quantile) {
            long target = (long) Math.ceil(count * quantile), seen = 0;
            if (maxDuration == 0) return 0;
            for (int i = 0; i < histogram.length; i++) {
                seen += histogram[i];
                if (seen >= target) return Math.scalb(1.0, i) / 1e6;
            }
            return 0;
        }
    }
}
