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
            return responseSummary(0, windowNanos);
        }
        Map<String, Object> responseSummary(long fromNanos, long toNanos) {
            if (fromNanos < 0 || toNanos <= fromNanos || toNanos > windowNanos)
                throw new IllegalArgumentException("Window must be inside the offered interval");
            var cohort = cohort(fromNanos, toNanos);
            double seconds = (toNanos - fromNanos) / 1e9;
            long lower = started + fromNanos;
            long upper = started + toNanos;
            var counts = new LinkedHashMap<String, Object>();
            for (Outcome outcome : Outcome.values()) counts.put(outcome.name().toLowerCase(java.util.Locale.ROOT),
                    cohort.stream().filter(s -> s.outcome == outcome).count());
            long sent = cohort.stream().filter(s -> s.sent != 0).count();
            long succeeded = cohort.stream().filter(s -> s.outcome == Outcome.SUCCEEDED).count();
            var response = cohort.stream().filter(s -> s.httpStatus != 0).map(s -> s.ended - s.sent).toList();
            var scheduledResponse = cohort.stream().filter(s -> s.httpStatus != 0).map(s -> s.ended - s.planned).toList();
            var success = cohort.stream().filter(s -> s.outcome == Outcome.SUCCEEDED).map(s -> s.ended - s.planned).toList();
            var lag = cohort.stream().filter(s -> s.sent != 0).map(s -> s.sent - s.planned).toList();
            long responsesInWindow = samples.stream().filter(s -> s.httpStatus != 0 && s.ended >= lower && s.ended < upper).count();
            var summary = new LinkedHashMap<String, Object>();
            summary.put("fromSeconds", fromNanos / 1e9);
            summary.put("toSeconds", toNanos / 1e9);
            summary.put("planned", cohort.size());
            summary.put("sent", sent);
            summary.put("outcomes", counts);
            summary.put("successRate", sent == 0 ? 0.0 : (double) succeeded / sent);
            summary.put("actualSendRate", sent / seconds);
            summary.put("sendsDuringWindowPerSecond", samples.stream()
                    .filter(s -> s.sent != 0 && s.sent >= lower && s.sent < upper).count() / seconds);
            summary.put("httpResponsesDuringWindow", responsesInWindow);
            summary.put("httpResponsesDuringWindowPerSecond", responsesInWindow / seconds);
            summary.put("successResponsesDuringWindowPerSecond", samples.stream()
                    .filter(s -> s.outcome == Outcome.SUCCEEDED && s.ended >= lower && s.ended < upper).count() / seconds);
            summary.put("successfulCohortPerSecond", succeeded / seconds);
            summary.put("responseLatencyMillis", percentiles(response));
            summary.put("scheduledResponseLatencyMillis", percentiles(scheduledResponse));
            summary.put("successfulCallLatencyMillis", percentiles(success));
            summary.put("scheduleLagMillis", percentiles(lag));
            summary.put("generatorLimited", sent != cohort.size() || percentile(lag, .99) > 100_000_000L);
            summary.put("outstandingHttpAtWindowStart", samples.stream()
                    .filter(s -> s.sent != 0 && s.sent < lower && s.ended >= lower).count());
            summary.put("outstandingHttpAtWindowEnd", samples.stream()
                    .filter(s -> s.sent != 0 && s.sent < upper && s.ended >= upper).count());
            return summary;
        }
        List<Sample> cohort(long fromNanos, long toNanos) {
            return samples.stream().filter(s -> s.planned >= started + fromNanos && s.planned < started + toNanos).toList();
        }
        Map<String, Object> summary() {
            return summary(0, windowNanos);
        }
        Map<String, Object> summary(long fromNanos, long toNanos) {
            var summary = responseSummary(fromNanos, toNanos);
            var samples = cohort(fromNanos, toNanos);
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
            summary.put("http429", samples.stream().filter(s -> s.httpStatus == 429).count());
            summary.put("unknownSubmissions", samples.stream().filter(s -> s.outcome == Outcome.UNKNOWN).count());
            summary.put("clientTimeouts", samples.stream().filter(s -> "client-timeout".equals(s.detail)).count());
            summary.put("acceptedResultsAfterDrain", Map.of(
                    "succeeded", samples.stream().filter(s -> s.accepted() && s.observed.equals("succeeded")).count(),
                    "failed", samples.stream().filter(s -> s.accepted() && s.observed.equals("failed")).count(),
                    "not_observed", samples.stream().filter(s -> s.accepted() && s.observed.equals("not_observed")).count()));
            summary.put("followupObservationMillis", percentiles(samples.stream()
                    .filter(s -> s.observedAfterWaitMillis >= 0).map(s -> s.observedAfterWaitMillis * 1_000_000).toList()));
            return summary;
        }
    }

    /**
     * Fast-fail checkpoint. Arrivals refused at the in-flight cap mean responses outlast their
     * wait: the server side is saturated. Otherwise a schedule-lag p99 above 100ms means the
     * generator itself could not keep pace.
     */
    static final long EARLY_CHECK_NANOS = 10_000_000_000L;
    static final long LATE_SEND_NANOS = 100_000_000L;
    static final String STOP_IN_FLIGHT_CAP = "in-flight-cap";
    static final String STOP_GENERATOR_LAG = "generator-lag";
    static final String STOP_PROTOCOL_ERROR = "protocol-error";

    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender) {
        return schedule(rate, seconds, capacity, prefix, executor, sender, System::nanoTime, CallLoad::awaitDeadline, null);
    }

    /** As {@link #schedule}, but stops offering load once {@code stop} holds a fast-fail reason. */
    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender,
                          java.util.concurrent.atomic.AtomicReference<String> stop) {
        return schedule(rate, seconds, capacity, prefix, executor, sender, System::nanoTime, CallLoad::awaitDeadline, stop);
    }

    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender,
                          LongSupplier clock, LongConsumer waitUntil) {
        return schedule(rate, seconds, capacity, prefix, executor, sender, clock, waitUntil, null);
    }

    static Batch schedule(int rate, int seconds, int capacity, String prefix, Executor executor, Sender sender,
                          LongSupplier clock, LongConsumer waitUntil,
                          java.util.concurrent.atomic.AtomicReference<String> stop) {
        if (rate < 1 || seconds < 1 || capacity < 1 || (long) rate * seconds > 300_000)
            throw new IllegalArgumentException("Invalid finite load bounds");
        int count = rate * seconds;
        var done = new CountDownLatch(count);
        var slots = new Semaphore(capacity);
        var samples = new ArrayList<Sample>(count);
        var sentCount = new java.util.concurrent.atomic.LongAdder();
        var lateCount = new java.util.concurrent.atomic.LongAdder();
        long notSent = 0;
        boolean checked = false;
        long start = clock.getAsLong();
        int offered = count;
        for (int i = 0; i < count; i++) {
            long planned = start + i * 1_000_000_000L / rate;
            if (stop != null) {
                if (!checked && planned - start >= EARLY_CHECK_NANOS) {
                    checked = true;
                    long sent = sentCount.sum();
                    if (notSent > 0) stop.compareAndSet(null, STOP_IN_FLIGHT_CAP);
                    else if (sent > 0 && lateCount.sum() * 100 > sent) stop.compareAndSet(null, STOP_GENERATOR_LAG);
                }
                if (stop.get() != null) {
                    offered = i;
                    for (int skipped = i; skipped < count; skipped++) done.countDown();
                    break;
                }
            }
            var sample = new Sample(prefix + "-" + i, planned);
            samples.add(sample);
            waitUntil.accept(planned);
            if (!slots.tryAcquire()) { notSent++; done.countDown(); continue; }
            int index = i;
            try {
                executor.execute(() -> {
                    sample.sent = clock.getAsLong();
                    sentCount.increment();
                    if (sample.sent - sample.planned > LATE_SEND_NANOS) lateCount.increment();
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
                        if (stop != null) stop.compareAndSet(null, STOP_PROTOCOL_ERROR);
                    } catch (java.net.http.HttpTimeoutException error) {
                        sample.outcome = Outcome.UNKNOWN;
                        sample.detail = "client-timeout";
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
        long window = offered == count ? seconds * 1_000_000_000L : Math.max(1, offered * 1_000_000_000L / rate);
        waitUntil.accept(start + window);
        return new Batch(List.copyOf(samples), start, window, done);
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
