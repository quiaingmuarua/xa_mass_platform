package com.xa.mass.api.sync;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges async task result events to blocking HTTP callers.
 *
 * <p>Callers register a {@link CompletableFuture} keyed by {@code taskId + messageId}.
 * When the engine fires {@code onTaskMessageLogicallyFinal}, the future is
 * completed and the waiting HTTP thread unblocks. Callers also get an
 * immediate runtime-state fallback lookup during await so they do not miss a
 * final result that raced ahead of registration.
 *
 * <p>The listener is registered after {@link ApplicationReadyEvent} so the
 * engine and task event surface are fully started before we hook in.
 */
@Component
public class SyncTaskResultBridge implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(SyncTaskResultBridge.class);

    public static final long MAX_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private final MassSdkApplication app;
    private final ConcurrentHashMap<String, CompletableFuture<TaskWorkFinalSnapshot>> pendingByMessage = new ConcurrentHashMap<>();

    public SyncTaskResultBridge(MassSdkApplication app) {
        this.app = app;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        app.addTaskWorkFinalListener(this::onWorkLogicallyFinal);
        logger.info("SyncTaskResultBridge listener registered");
    }

    private void onWorkLogicallyFinal(TaskWorkFinalNotification notification) {
        if (notification == null || notification.finalSnapshot() == null) {
            return;
        }
        String taskId = notification.taskId();
        String messageId = notification.finalSnapshot().messageId();
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        CompletableFuture<TaskWorkFinalSnapshot> future = pendingByMessage.remove(messageKey(taskId, messageId));
        if (future != null) {
            future.complete(notification.finalSnapshot());
        }
    }

    public CompletableFuture<TaskWorkFinalSnapshot> register(String taskId, String messageId) {
        return pendingByMessage.computeIfAbsent(messageKey(taskId, messageId), key -> new CompletableFuture<>());
    }

    public Optional<TaskWorkFinalSnapshot> getExistingFinal(String taskId, String messageId) {
        return app.getTaskWorkFinal(taskId, messageId);
    }

    public void unregister(String taskId,
                           String messageId,
                           CompletableFuture<TaskWorkFinalSnapshot> future) {
        if (future == null) {
            return;
        }
        pendingByMessage.remove(messageKey(taskId, messageId), future);
    }

    /**
     * Blocks the calling thread until the future completes or {@code timeoutMs}
     * (capped at {@link #MAX_TIMEOUT_MS}) elapses.
     *
     * @return the completed logical-final event, or empty on timeout / interrupt
     */
    public Optional<TaskWorkFinalSnapshot> await(String taskId,
                                                 String messageId,
                                                 CompletableFuture<TaskWorkFinalSnapshot> future,
                                                 long timeoutMs) {
        String key = messageKey(taskId, messageId);
        Optional<TaskWorkFinalSnapshot> existing = getExistingFinal(taskId, messageId);
        if (existing.isPresent()) {
            pendingByMessage.remove(key, future);
            future.complete(existing.get());
            return existing;
        }
        long bounded = Math.max(1L, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        try {
            return Optional.of(future.get(bounded, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            pendingByMessage.remove(key, future);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingByMessage.remove(key, future);
            return Optional.empty();
        } catch (Exception e) {
            pendingByMessage.remove(key, future);
            throw new IllegalStateException("Sync result await failed: " + e.getMessage(), e);
        }
    }

    private String messageKey(String taskId, String messageId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return taskId.trim() + "|" + messageId.trim();
    }
}
