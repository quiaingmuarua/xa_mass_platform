package com.xa.mass.transport.runtime;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-owned worker adapter wrapper for concrete protocol adapters that
 * delegate dispatch I/O to an adapter-owned {@link TaskDispatchChannel}.
 */
public class DelegatingWorkerAdapter implements WorkerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DelegatingWorkerAdapter.class);

    private final String adapterId;
    private final String transportHint;
    private final Set<String> aliases;
    private final TaskDispatchChannel taskDispatchChannel;
    private final String unavailableReason;

    public DelegatingWorkerAdapter(String adapterId,
                                   String transportHint,
                                   Set<String> aliases,
                                   TaskDispatchChannel taskDispatchChannel,
                                   String unavailableReason) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.transportHint = requireText(transportHint, "transportHint");
        this.aliases = aliases == null || aliases.isEmpty() ? Set.of() : Set.copyOf(aliases);
        this.taskDispatchChannel = taskDispatchChannel;
        this.unavailableReason = unavailableReason == null || unavailableReason.isBlank()
                ? "dispatch channel is unavailable"
                : unavailableReason.trim();
    }

    @Override
    public String protocol() {
        return adapterId;
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public String transportHint() {
        return transportHint;
    }

    @Override
    public Set<String> aliases() {
        return aliases;
    }

    @Override
    public List<DispatchOutcome> dispatchTaskItems(List<TaskDispatchItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (taskDispatchChannel == null) {
            logger.warn("Skip task dispatch because adapter dispatch channel is unavailable: adapterId={}", adapterId);
            return RuntimeDispatchOutcomes.adapterUnavailable(adapterId, items, unavailableReason);
        }
        return taskDispatchChannel.dispatchTaskItems(items);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
