package com.xa.mass.integration.workercallperformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Finite open-loop offered load. Scheduling never waits for HTTP capacity. */
final class CallLoad {
    enum Outcome { SUCCEEDED, FAILED, NOT_OBSERVED, UNKNOWN, NOT_SENT, PROTOCOL_ERROR, REJECTED, TIMED_OUT }
    record Reply(int httpStatus, Outcome outcome, String directCallId, String detail) {
        Reply(int httpStatus, Outcome outcome) { this(httpStatus, outcome, null, null); }
    }
    @FunctionalInterface interface Sender { Reply send(String id, int index) throws Exception; }
    static final class ProtocolFailure extends IllegalStateException {
        ProtocolFailure(String message) { super(message); }
    }

    static final class Sample {
        final String id;
        final long planned;
        long sent;
        long ended;
        int httpStatus;
        String directCallId;
        String detail;
        Outcome outcome = Outcome.NOT_SENT;
        String observed = "not_observed";
        long observedAfterWaitMillis = -1;

        Sample(String id, long planned) { this.id = id; this.planned = planned; }
        boolean accepted() { return httpStatus == 200; }
        Map<String, Object> evidence(long started) {
            var row = new LinkedHashMap<String, Object>();
            row.put("messageId", id);
            row.put("plannedOffsetMillis", millis(planned - started));
            row.put("sentOffsetMillis", sent == 0 ? -1 : millis(sent - started));
            row.put("endedOffsetMillis", ended == 0 ? -1 : millis(ended - started));
            row.put("httpStatus", httpStatus);
            if (directCallId != null) row.put("directCallId", directCallId);
            if (detail != null) row.put("detail", detail);
            row.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
            row.put("accepted", accepted());
            row.put("observedResult", observed);
            row.put("observedAfterWaitMillis", observedAfterWaitMillis);
            return row;
        }
    }

    record Batch(List<Sample> samples, long started, long windowNanos, CountDownLatch completed) {
        void await() throws InterruptedException {
            if (!completed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("HTTP tasks did not stop");
        }
        Map<String, Object> responseSummary() {
            var counts = new LinkedHashMap<String, Object>();
            for (Outcome outcome : Outcome.values()) counts.put(outcome.name().toLowerCase(java.util.Locale.ROOT),
                    samples.stream().filter(s -> s.outcome == outcome).count());
            long sent = samples.stream().filter(s -> s.sent != 0).count();
            long succeeded = samples.stream().filter(s -> s.outcome == Outcome.SUCCEEDED).count();
            var response = samples.stream().filter(s -> s.httpStatus != 0).map(s -> s.ended - s.sent).toList();
            var scheduledResponse = samples.stream().filter(s -> s.httpStatus != 0).map(s -> s.ended - s.planned).toList();
            var success = samples.stream().filter(s -> s.outcome == Outcome.SUCCEEDED).map(s -> s.ended - s.planned).toList();
            var lag = samples.stream().filter(s -> s.sent != 0).map(s -> s.sent - s.planned).toList();
            var summary = new LinkedHashMap<String, Object>();
            summary.put("planned", samples.size());
            summary.put("sent", sent);
            summary.put("outcomes", counts);
            summary.put("successRate", sent == 0 ? 0.0 : (double) succeeded / sent);
            summary.put("actualSendRate", sent / (windowNanos / 1e9));
            summary.put("successResponsesDuringWindowPerSecond", samples.stream()
                    .filter(s -> s.outcome == Outcome.SUCCEEDED && s.ended <= started + windowNanos).count() / (windowNanos / 1e9));
            summary.put("successfulCohortPerSecond", succeeded / (windowNanos / 1e9));
            summary.put("responseLatencyMillis", percentiles(response));
            summary.put("scheduledResponseLatencyMillis", percentiles(scheduledResponse));
            summary.put("successfulCallLatencyMillis", percentiles(success));
            summary.put("scheduleLagMillis", percentiles(lag));
            summary.put("generatorLimited", sent != samples.size() || percentile(lag, .99) > 100_000_000L);
            summary.put("outstandingHttpAtWindowEnd", samples.stream()
                    .filter(s -> s.sent != 0 && s.ended > started + windowNanos).count());
            return summary;
        }
        Map<String, Object> summary() {
            var summary = responseSummary();
            summary.put("accepted", samples.stream().filter(Sample::accepted).count());
            summary.put("unresolvedAcceptedIds", samples.stream().filter(s -> s.accepted() && s.observed.equals("not_observed"))
                    .map(s -> s.id).toList());
            summary.put("resultCounts", Map.of(
                    "succeeded", samples.stream().filter(s -> s.observed.equals("succeeded")).count(),
                    "failed", samples.stream().filter(s -> s.observed.equals("failed")).count(),
                    "not_observed", samples.stream().filter(s -> s.observed.equals("not_observed")).count()));
            long accepted = samples.stream().filter(Sample::accepted).count();
            summary.put("acceptedSuccessRateAfterDrain", accepted == 0 ? 0.0 : samples.stream()
                    .filter(s -> s.accepted() && s.observed.equals("succeeded")).count() / (double) accepted);
            summary.put("acceptedUnobservedAfterResponses", samples.stream()
                    .filter(s -> s.outcome == Outcome.NOT_OBSERVED).count());
            summary.put("followupObservationMillis", percentiles(samples.stream()
                    .filter(s -> s.observedAfterWaitMillis >= 0).map(s -> s.observedAfterWaitMillis * 1_000_000).toList()));
            return summary;
        }
    }

    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender) {
        return schedule(rate, seconds, capacity, prefix, executor, sender, System::nanoTime, CallLoad::awaitDeadline);
    }

    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender,
                          LongSupplier clock, LongConsumer waitUntil) {
        if (rate < 1 || seconds < 1 || capacity < 1 || (long) rate * seconds > 150_000)
            throw new IllegalArgumentException("Invalid finite load bounds");
        int count = rate * seconds;
        var done = new CountDownLatch(count);
        var slots = new Semaphore(capacity);
        var samples = new ArrayList<Sample>(count);
        long start = clock.getAsLong();
        for (int i = 0; i < count; i++) {
            long planned = start + i * 1_000_000_000L / rate;
            var sample = new Sample(prefix + "-" + i, planned);
            samples.add(sample);
            waitUntil.accept(planned);
            if (!slots.tryAcquire()) { done.countDown(); continue; }
            int index = i;
            try {
                executor.execute(() -> {
                    sample.sent = clock.getAsLong();
                    try {
                        var reply = sender.send(sample.id, index);
                        sample.httpStatus = reply.httpStatus();
                        sample.outcome = reply.outcome();
                        sample.directCallId = reply.directCallId();
                        sample.detail = reply.detail();
                        if (reply.outcome() == Outcome.SUCCEEDED) sample.observed = "succeeded";
                        if (reply.outcome() == Outcome.FAILED) sample.observed = "failed";
                    } catch (ProtocolFailure error) {
                        sample.httpStatus = 200;
                        sample.outcome = Outcome.PROTOCOL_ERROR;
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        sample.outcome = Outcome.UNKNOWN;
                    } catch (Exception error) {
                        sample.outcome = Outcome.UNKNOWN;
                    } finally {
                        sample.ended = clock.getAsLong();
                        slots.release();
                        done.countDown();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                slots.release(); done.countDown();
            }
        }
        waitUntil.accept(start + seconds * 1_000_000_000L);
        return new Batch(List.copyOf(samples), start, seconds * 1_000_000_000L, done);
    }

    static void awaitDeadline(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Load interrupted");
            LockSupport.parkNanos(remaining);
        }
    }

    static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    private static Map<String, Object> percentiles(List<Long> values) {
        return Map.of("samples", values.size(), "p50", millis(percentile(values, .50)),
                "p95", millis(percentile(values, .95)), "p99", millis(percentile(values, .99)));
    }
    private static double millis(long nanos) { return nanos / 1e6; }
}
