package com.xa.mass.workersimulator.messaging;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import static com.xa.mass.workersimulator.messaging.MessageProtocol.*;

/** Fixed Host composition. Mutable sending and receiving facts remain with their Owners. */
public final class MessageScenario implements AutoCloseable {
    private final MessageWorkerEndpoint worker;
    private final MessageLab lab;
    private final MessageLabHttp http;
    private final Map<String, MessageContentMode> modes;
    private ScheduledExecutorService ticker;
    private boolean closed;

    public MessageScenario(long seed, MessageSettings settings, Map<String, MessageContentMode> modes) {
        this(seed, settings, modes, System::currentTimeMillis, System::nanoTime);
    }
    MessageScenario(long seed, MessageSettings settings, Map<String, MessageContentMode> modes,
            LongSupplier clock, LongSupplier monotonic) {
        this.modes = Map.copyOf(modes);
        http = new MessageLabHttp();
        worker = new MessageWorkerEndpoint(http::send, settings, monotonic);
        lab = new MessageLab(seed, settings, clock, monotonic, this::dispatchReceipt);
    }

    public MessageWorkerEndpoint worker() { return worker; }
    public MessageLab lab() { return lab; }

    /** Bind business I/O before Workers connect; callbacks need not wait for Host READY. */
    public synchronized void startHttp(URI listener) {
        if (closed) throw new IllegalStateException("Messages closed");
        if (ticker != null) throw new IllegalStateException("Messages HTTP already started");
        http.start(listener);
        try {
            worker.start();
            ticker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "message-lab-actions"); thread.setDaemon(true); return thread;
            });
            ticker.scheduleWithFixedDelay(() -> { worker.expireAssociations(); lab.tick(); }, 100, 100, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            close(); throw failure;
        }
    }

    /** The sole cross-Owner admission: sender correlation and receiver retention cannot race stop. */
    public Map<String, Object> accept(Map<String, Object> input) {
        if (!input.keySet().equals(Set.of("message", "sender", "callbackId")))
            throw new IllegalArgumentException("Invalid send envelope");
        Map<String, Object> sender = object(input, "sender");
        return worker.admitReceiver(sender, nullableCallback(input), usable -> {
            MessageContentMode mode = modes.get(text(sender, "workerGroupId", 256));
            if (mode == null) throw new IllegalArgumentException("Unknown sender Group content mode");
            return lab.accept(object(input, "message"), sender, usable, mode);
        });
    }

    private boolean dispatchReceipt(MessageLab.Receipt receipt) {
        if (!worker.admitsCallbacks()) return false;
        return http.offer(receipt, result -> lab.callbackCompleted(receipt, result.status(), result.reported(), result.failure()));
    }

    public void stop(MessageWorkerEndpoint.Sender sender) {
        worker.stopWithCleanup(sender, () -> lab.stop(sender.group(), sender.replica()));
    }

    /** Two observation snapshots, not an additional state owner or atomic global snapshot. */
    public Map<String, Object> metrics() {
        var result = new LinkedHashMap<>(lab.metrics()); result.putAll(worker.metrics()); return result;
    }

    @Override public void close() {
        ScheduledExecutorService closing;
        synchronized (this) {
            if (closed) return;
            closed = true; worker.close(); closing = ticker;
        }
        if (closing != null) closing.shutdownNow();
        lab.close(); http.close();
        // No synchronous flush, replay or wait for an admitted Handler/callback.
    }
}
