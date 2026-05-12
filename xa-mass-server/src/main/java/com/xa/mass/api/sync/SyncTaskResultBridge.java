package com.xa.mass.api.sync;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges async task result events to blocking HTTP callers.
 *
 * <p>Callers register a {@link CompletableFuture} keyed by a correlation ID
 * embedded in the task's sharedConfig before creating the task. When the engine
 * fires {@code onTaskMessageLogicallyFinal}, the future is completed and the
 * waiting HTTP thread unblocks.
 *
 * <p>The listener is registered after {@link ApplicationReadyEvent} so the
 * engine and task event surface are fully started before we hook in.
 */
@Component
@Profile("dev")
public class SyncTaskResultBridge implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(SyncTaskResultBridge.class);

    public static final long MAX_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    public static final String SYNC_KEY = "_syncKey";

    private final MassSdkApplication app;
    private final ConcurrentHashMap<String, CompletableFuture<TaskWorkFinalSnapshot>> pending = new ConcurrentHashMap<>();

    public SyncTaskResultBridge(MassSdkApplication app) {
        this.app = app;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        app.addTaskWorkFinalListener(this::onWorkLogicallyFinal);
        logger.info("SyncTaskResultBridge listener registered");
    }

    private void onWorkLogicallyFinal(TaskWorkFinalNotification notification) {
        String correlationId = resolveCorrelationId(notification == null ? null : notification.sharedConfig());
        if (correlationId == null) {
            return;
        }
        CompletableFuture<TaskWorkFinalSnapshot> future = pending.remove(correlationId);
        if (future != null) {
            future.complete(notification.finalSnapshot());
        }
    }

    private String resolveCorrelationId(Map<String, Object> sharedConfig) {
        if (sharedConfig == null || sharedConfig.isEmpty()) {
            return null;
        }
        Object value = sharedConfig.get(SYNC_KEY);
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * Registers a future keyed by {@code correlationId}. Must be called
     * <em>before</em> the task is created to avoid a timing gap.
     */
    public CompletableFuture<TaskWorkFinalSnapshot> register(String correlationId) {
        return pending.computeIfAbsent(correlationId, k -> new CompletableFuture<>());
    }

    /**
     * Blocks the calling thread until the future completes or {@code timeoutMs}
     * (capped at {@link #MAX_TIMEOUT_MS}) elapses.
     *
     * @return the completed logical-final event, or empty on timeout / interrupt
     */
    public Optional<TaskWorkFinalSnapshot> await(String correlationId,
                                                 CompletableFuture<TaskWorkFinalSnapshot> future,
                                                 long timeoutMs) {
        long bounded = Math.max(1L, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        try {
            return Optional.of(future.get(bounded, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            pending.remove(correlationId, future);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(correlationId, future);
            return Optional.empty();
        } catch (Exception e) {
            pending.remove(correlationId, future);
            throw new IllegalStateException("Sync result await failed: " + e.getMessage(), e);
        }
    }
}
