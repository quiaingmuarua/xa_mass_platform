package com.xa.mass.command.event;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Timeout-aware event runtime decorator for isolating direct runtime handlers.
 *
 * <p>Timeout cancellation is cooperative. A handler that ignores interruption
 * can continue running until it returns, but the caller receives a bounded
 * failure response instead of blocking indefinitely.
 */
public final class BoundedMassEventRuntime implements MassEventRuntime {

    public static final String EVENT_TIMEOUT = "EVENT_TIMEOUT";
    public static final String EVENT_REJECTED = "EVENT_REJECTED";
    public static final String EVENT_INTERRUPTED = "EVENT_INTERRUPTED";
    public static final String EVENT_ERROR = "EVENT_ERROR";

    private final MassEventRuntime delegate;
    private final RuntimeTaskExecutor executor;
    private final long timeoutMillis;

    public BoundedMassEventRuntime(MassEventRuntime delegate,
                                   RuntimeTaskExecutor executor,
                                   long timeoutMillis) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void register(CoreEventDescriptor descriptor, MassEventHandler handler) {
        delegate.register(descriptor, handler);
    }

    @Override
    public void registerOrReplace(CoreEventDescriptor descriptor, MassEventHandler handler) {
        delegate.registerOrReplace(descriptor, handler);
    }

    @Override
    public CoreEventResponse dispatch(CoreEventRequest request, CoreEventPrincipal principal) {
        CoreEventRequest normalizedRequest = Objects.requireNonNull(request, "request");
        Future<CoreEventResponse> future;
        try {
            future = executor.submit(() -> delegate.dispatch(normalizedRequest, principal));
        } catch (RejectedExecutionException e) {
            return CoreEventResponse.failure(EVENT_REJECTED, e.getMessage(), normalizedRequest.getRequestId());
        }

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return CoreEventResponse.failure(
                    EVENT_TIMEOUT,
                    "event handler timed out after " + timeoutMillis + " ms",
                    normalizedRequest.getRequestId()
            );
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return CoreEventResponse.failure(EVENT_INTERRUPTED, "event dispatch interrupted",
                    normalizedRequest.getRequestId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return CoreEventResponse.failure(EVENT_ERROR, cause.getMessage(), normalizedRequest.getRequestId());
        }
    }

    @Override
    public CoreEventDescriptor getDescriptor(String event) {
        return delegate.getDescriptor(event);
    }

    @Override
    public List<CoreEventDescriptor> listDescriptors() {
        return delegate.listDescriptors();
    }

    @Override
    public boolean contains(String event) {
        return delegate.contains(event);
    }
}
