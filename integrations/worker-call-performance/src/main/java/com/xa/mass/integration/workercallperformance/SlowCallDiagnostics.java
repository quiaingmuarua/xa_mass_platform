package com.xa.mass.integration.workercallperformance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * Bounded slow-call evidence: the slowest calls of fixed Owner stages and what their own
 * thread did meanwhile (parked, socket I/O, monitor waits, CPU samples), with overlapping GC
 * pauses and machine CPU load. Stacks pass the caller's whitelist; keys and payloads never enter.
 */
final class SlowCallDiagnostics {
    static final Set<String> STAGES = Set.of(
            "xa.mass.HttpInitial/REPORT_APPEND", "xa.mass.HttpInitial/COMMAND_CONSUME",
            "xa.mass.AdapterRemote/REPORT_APPEND", "xa.mass.AdapterRemote/COMMAND_CONSUME",
            "xa.mass.TaskResult/RESULT_PROCESS", "xa.mass.TaskResult/TASK_RESULT_CONSUME",
            "xa.mass.TaskResult/WORKER_RELEASE", "xa.mass.TaskDispatch/DISPATCH_ROUND",
            "xa.mass.TaskDispatch/COMMAND_APPEND", "xa.mass.TaskDispatch/ITEM_CLAIM",
            "xa.mass.TaskDispatch/WORKER_CONFIRM");
    static final int SLOWEST_PER_STAGE = 10;
    static final int MAX_CALLS = 200;
    static final long ALWAYS_KEEP_NANOS = 100_000_000L;
    private static final Set<String> ACTIVITY = Set.of(
            "jdk.ThreadPark", "jdk.SocketRead", "jdk.SocketWrite", "jdk.JavaMonitorEnter", "jdk.ExecutionSample");
    private static final int MAX_TRACKED_CALLS = 200_000;
    private static final int MAX_ACTIVITY = 500_000;
    private static final long MARGIN_MILLIS = 5_000;

    private record Call(String stage, long startMillis, long nanos, long thread, String threadName,
                        Long batchSize, Long count) {
        long endMillis() { return startMillis + nanos / 1_000_000; }
    }

    private record Activity(String type, long startMillis, long endMillis, String site) {
    }

    private final long started;
    private final int seconds;
    private final List<Call> calls = new ArrayList<>();
    private final Map<Long, List<Activity>> activityByThread = new HashMap<>();
    private final List<long[]> gcPauses = new ArrayList<>();
    private final List<double[]> cpuLoads = new ArrayList<>();
    private int activityCount;
    private boolean overflow;

    SlowCallDiagnostics(long started, int seconds) {
        this.started = started;
        this.seconds = seconds;
    }

    /** Thread activity, GC pauses and CPU load around the window; the caller supplies the safe site stack. */
    void observe(RecordedEvent event, String type, long startMillis, String site) {
        long endMillis = event.getEndTime().toEpochMilli();
        if (endMillis < started - MARGIN_MILLIS || startMillis > started + seconds * 1000L + MARGIN_MILLIS) return;
        if (type.equals("jdk.GCPhasePause")) {
            gcPauses.add(new long[]{startMillis, endMillis});
        } else if (type.equals("jdk.CPULoad")) {
            cpuLoads.add(new double[]{startMillis, event.getFloat("machineTotal"),
                    event.getFloat("jvmUser") + event.getFloat("jvmSystem")});
        } else if (ACTIVITY.contains(type)) {
            RecordedThread thread = type.equals("jdk.ExecutionSample") ? event.getThread("sampledThread") : event.getThread();
            if (thread == null) return;
            if (activityCount == MAX_ACTIVITY) { overflow = true; return; }
            activityCount++;
            activityByThread.computeIfAbsent(thread.getJavaThreadId(), ignored -> new ArrayList<>())
                    .add(new Activity(type, startMillis, endMillis, site));
        }
    }

    /** One aggregate Owner stage event inside the measurement window. */
    void call(RecordedEvent event, String stage, long startMillis, long nanos) {
        if (!STAGES.contains(stage)) return;
        if (calls.size() == MAX_TRACKED_CALLS) { overflow = true; return; }
        RecordedThread thread = event.getThread();
        calls.add(new Call(stage, startMillis, nanos, thread == null ? -1 : thread.getJavaThreadId(),
                thread == null ? null : threadName(thread.getJavaName()),
                event.hasField("batchSize") ? ((Number) event.getValue("batchSize")).longValue() : null,
                event.hasField("count") ? ((Number) event.getValue("count")).longValue() : null));
    }

    Map<String, Object> summary() {
        var byStage = new LinkedHashMap<String, List<Call>>();
        calls.forEach(call -> byStage.computeIfAbsent(call.stage(), ignored -> new ArrayList<>()).add(call));
        var selected = new ArrayList<Call>();
        byStage.values().forEach(stageCalls -> {
            stageCalls.sort(Comparator.comparingLong(Call::nanos).reversed());
            for (int i = 0; i < stageCalls.size(); i++) {
                if (i < SLOWEST_PER_STAGE || stageCalls.get(i).nanos() >= ALWAYS_KEEP_NANOS) selected.add(stageCalls.get(i));
            }
        });
        selected.sort(Comparator.comparingLong(Call::nanos).reversed());
        boolean truncated = selected.size() > MAX_CALLS;
        var result = new LinkedHashMap<String, Object>();
        result.put("meaning", "Per stage the " + SLOWEST_PER_STAGE + " slowest calls plus every call of at least "
                + ALWAYS_KEEP_NANOS / 1_000_000 + "ms, capped at " + MAX_CALLS + ". Thread fields cover only the call's own "
                + "thread: parked, socket and monitor time are JFR events above their 10ms thresholds, cpuSamples are 20ms "
                + "execution samples. gcPauseMillis and machine CPU cover the whole process and host.");
        result.put("trackedCalls", calls.size());
        result.put("truncated", truncated);
        result.put("overflow", overflow);
        result.put("calls", selected.stream().limit(MAX_CALLS).map(this::describe).toList());
        return result;
    }

    private Map<String, Object> describe(Call call) {
        long start = call.startMillis(), end = Math.max(call.endMillis(), start + 1);
        var result = new LinkedHashMap<String, Object>();
        result.put("stage", call.stage());
        result.put("startOffsetMillis", start - started);
        result.put("durationMillis", call.nanos() / 1e6);
        result.put("thread", call.threadName());
        if (call.batchSize() != null) result.put("batchSize", call.batchSize());
        if (call.count() != null) result.put("count", call.count());
        var millis = new LinkedHashMap<String, Long>();
        var events = new LinkedHashMap<String, Long>();
        var parkSites = new HashMap<String, Long>();
        var cpuSites = new HashMap<String, Long>();
        long cpuSamples = 0;
        for (Activity activity : activityByThread.getOrDefault(call.thread(), List.of())) {
            if (activity.type().equals("jdk.ExecutionSample")) {
                if (activity.startMillis() >= start && activity.startMillis() <= end) {
                    cpuSamples++;
                    if (activity.site() != null) cpuSites.merge(activity.site(), 1L, Long::sum);
                }
                continue;
            }
            long overlap = Math.min(end, activity.endMillis()) - Math.max(start, activity.startMillis());
            if (overlap <= 0) continue;
            String name = activity.type().substring("jdk.".length());
            millis.merge(name, overlap, Long::sum);
            events.merge(name, 1L, Long::sum);
            if (activity.type().equals("jdk.ThreadPark") && activity.site() != null) parkSites.merge(activity.site(), overlap, Long::sum);
        }
        result.put("threadWaitMillis", millis);
        result.put("threadWaitEvents", events);
        result.put("cpuSamples", cpuSamples);
        result.put("topParkSite", top(parkSites));
        result.put("topCpuSite", top(cpuSites));
        long gc = 0;
        for (long[] pause : gcPauses) gc += Math.max(0, Math.min(end, pause[1]) - Math.max(start, pause[0]));
        result.put("gcPauseMillis", gc);
        double[] load = cpuLoads.stream().min(Comparator.comparingDouble(sample -> Math.abs(sample[0] - start))).orElse(null);
        result.put("machineCpuFraction", load == null ? null : load[1]);
        result.put("processCpuFraction", load == null ? null : load[2]);
        return result;
    }

    private static String top(Map<String, Long> weights) {
        return weights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Thread names keep their role; numbers such as pool indexes, ports and addresses are masked. */
    static String threadName(String name) {
        if (name == null) return null;
        String masked = name.replaceAll("\\d+", "#");
        return masked.length() > 80 ? masked.substring(0, 80) : masked;
    }
}
