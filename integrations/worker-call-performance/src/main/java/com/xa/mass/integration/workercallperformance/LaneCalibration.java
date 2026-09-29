package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * Stage 0 host calibration: a fixed JVM CPU workload measured before the lane world starts, so
 * results from differently fast runners can be normalized or excluded. It measures the host, not
 * the platform; the workload never changes between lane versions.
 */
final class LaneCalibration {
    static final int WARMUP_MILLIS = 1_000;
    static final int MEASURE_MILLIS = 2_000;
    static final int PARALLEL_THREADS = 4;
    private static final byte[] INPUT = CallApi.INPUT.getBytes(StandardCharsets.UTF_8);

    private LaneCalibration() {}

    static void run(Map<String, String> options) throws Exception {
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", "calibrate");
        result.put("workload", "md5-64-bytes");
        result.put("cpuSingleOpsPerSecond", measure(1));
        result.put("cpuParallelOpsPerSecond", measure(PARALLEL_THREADS));
        result.put("parallelThreads", PARALLEL_THREADS);
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("status", "passed");
        Files.writeString(output.resolve("calibrate.json"), Jsons.toJson(result), StandardOpenOption.CREATE_NEW);
    }

    /** Total MD5 operations per second across {@code threads} threads, after a fixed warmup. */
    static double measure(int threads) throws Exception {
        try (var executor = Executors.newFixedThreadPool(threads)) {
            run(executor, threads, WARMUP_MILLIS);
            var counts = run(executor, threads, MEASURE_MILLIS);
            return counts.stream().mapToLong(Long::longValue).sum() / (MEASURE_MILLIS / 1000.0);
        }
    }

    private static List<Long> run(java.util.concurrent.ExecutorService executor, int threads, int millis) throws Exception {
        var tasks = new ArrayList<Callable<Long>>();
        for (int i = 0; i < threads; i++) tasks.add(() -> digestFor(millis));
        var counts = new ArrayList<Long>();
        for (var future : executor.invokeAll(tasks)) counts.add(future.get());
        return counts;
    }

    static long digestFor(int millis) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long operations = 0;
        byte sink = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 256; i++) sink ^= digest.digest(INPUT)[0];
            operations += 256;
        }
        // Keep the result observable so the loop cannot be removed.
        if (sink == 42 && operations < 0) System.out.print("");
        return operations;
    }
}
