package com.xa.mass.workersimulator.sms;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Finite SMS state and external stimuli. Worker resources belong to the common Host. */
public final class SmsScenario implements AutoCloseable {
    public static final String START_EVENT = "extension.worker.sms.listen.start";
    public static final String CANCEL_EVENT = "extension.worker.sms.listen.cancel";
    public final ListeningRegistry registry = new ListeningRegistry();
    private final Traffic traffic = new Traffic();
    private ScheduledExecutorService clock;
    private volatile boolean closed;

    public List<WorkerEventDefinition<?>> definitions(ListeningRegistry.Sim sim) {
        return List.of(WorkerEventDefinition.extension("sms.listen.start", WorkerEventParameterResolvers.jsonMap(),
                        (request, reporter) -> Jsons.toJson(registry.listen(sim, request, reporter))),
                WorkerEventDefinition.extension("sms.listen.cancel", WorkerEventParameterResolvers.jsonMap(),
                        request -> Jsons.toJson(registry.cancel(sim, request))));
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("SMS scenario closed");
        if (clock != null) return;
        clock = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "scenario-sms-clock");
            thread.setDaemon(true);
            return thread;
        });
        clock.scheduleWithFixedDelay(registry::expire, 100, 100, TimeUnit.MILLISECONDS);
        clock.scheduleWithFixedDelay(traffic::tick, 10, 10, TimeUnit.MILLISECONDS);
    }

    public Map<String, Object> metrics() { return Map.of("host", registry.metrics(), "traffic", traffic.snapshot()); }
    public Map<String, Object> startTraffic(Map<String, Object> input) { return traffic.start(input); }
    public Map<String, Object> stopTraffic() { return traffic.stop(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        traffic.stop();
        if (clock != null) clock.shutdownNow();
        registry.close();
    }

    /** Called after Worker revocation, never while a number gate is held. */
    public void awaitClosed() {
        ScheduledExecutorService ending;
        synchronized (this) { ending = clock; }
        if (ending != null) {
            try { ending.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    private final class Traffic {
        private final Random random = new Random(20260909L);
        private long next;
        private long deadline;
        private long generated;
        private int rate;
        private long sequence;
        private String error = "";
        private List<ListeningRegistry.Sim> devices = List.of();

        synchronized Map<String, Object> start(Map<String, Object> input) {
            if (closed) throw new IllegalStateException("SMS scenario closed");
            if (rate != 0) throw new IllegalStateException("Traffic is already running");
            int selectedRate = Math.toIntExact(ListeningRegistry.number(input, "ratePerSecond"));
            long duration = ListeningRegistry.number(input, "durationSeconds");
            if (selectedRate < 1 || selectedRate > 1000 || duration < 1 || duration > 300
                    || selectedRate * duration > 100_000) throw new IllegalArgumentException("Invalid finite traffic budget");
            devices = registry.devices();
            if (devices.isEmpty()) throw new IllegalStateException("No SMS devices discovered");
            rate = selectedRate; next = System.nanoTime(); deadline = next + duration * 1_000_000_000L;
            generated = 0; error = "";
            return snapshot();
        }

        synchronized Map<String, Object> stop() { rate = 0; return snapshot(); }
        synchronized Map<String, Object> snapshot() {
            return Map.of("running", rate != 0, "ratePerSecond", rate, "generated", generated, "error", error);
        }

        void tick() {
            // Bound catch-up work; a stalled timer must not generate an unbounded burst.
            for (int i = 0; i < 32; i++) {
                ListeningRegistry.Sim device;
                String text;
                String smsId;
                synchronized (this) {
                    long now = System.nanoTime();
                    if (rate == 0 || now < next) return;
                    if (now >= deadline) { rate = 0; return; }
                    device = devices.get(random.nextInt(devices.size()));
                    int type = random.nextInt(3);
                    text = type == 2 ? "Preview service announcement" : (type == 0 ? "[A] " : "[B] ")
                            + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                    smsId = "auto-" + sequence++;
                    next += 1_000_000_000L / rate;
                    generated++;
                }
                try { registry.receive(device, smsId, text); }
                catch (RuntimeException failure) {
                    synchronized (this) { error = "SMS capacity or input rejected"; rate = 0; }
                    return;
                }
            }
        }
    }
}
