package com.xa.mass.worker.runtime;

import java.util.Objects;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import com.xa.mass.transport.client.WorkerTransportType;

/** One completed admission evaluation; a cooldown never contains an Endpoint. */
public final class WorkerPreparationResult {
    private final String workerId;
    private final PreparedWorker preparedWorker;
    private final Long cooldownUntilMillis;

    private WorkerPreparationResult(String workerId, PreparedWorker preparedWorker, Long cooldownUntilMillis) {
        this.workerId = workerId;
        this.preparedWorker = preparedWorker;
        this.cooldownUntilMillis = cooldownUntilMillis;
    }

    public static WorkerPreparationResult prepared(PreparedWorker preparedWorker) {
        Objects.requireNonNull(preparedWorker, "preparedWorker");
        return new WorkerPreparationResult(preparedWorker.workerId(), preparedWorker, null);
    }

    public static WorkerPreparationResult cooldown(String workerId, long untilMillis) {
        if (workerId == null || workerId.isBlank() || untilMillis <= 0) {
            throw new IllegalArgumentException("Cooldown requires workerId and a positive deadline");
        }
        return new WorkerPreparationResult(workerId, null, untilMillis);
    }

    public String workerId() { return workerId; }
    public PreparedWorker preparedWorker() { return preparedWorker; }
    public Long cooldownUntilMillis() { return cooldownUntilMillis; }

    /** Strict shared HTTP response parsing; credentials remain platform request-local. */
    public static WorkerPreparationResult decode(Map<String, Object> fields, WorkerTransportType transport) {
        Object rawId = fields.get("workerId");
        if (!(rawId instanceof String) || ((String) rawId).isBlank()) {
            throw new IllegalArgumentException("Invalid preparation identity");
        }
        String workerId = (String) rawId;
        if ("cooldown".equals(fields.get("status"))) {
            if (transport != WorkerTransportType.WEBSOCKET
                    || !fields.keySet().equals(Set.of("status", "workerId", "cooldownUntilMillis"))
                    || !(fields.get("cooldownUntilMillis") instanceof Number)) {
                throw new IllegalArgumentException("Invalid cooldown response");
            }
            try {
                return cooldown(workerId, new BigDecimal(fields.get("cooldownUntilMillis").toString()).longValueExact());
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid cooldown deadline", invalid);
            }
        }
        if (!"prepared".equals(fields.get("status"))
                || !fields.keySet().equals(Set.of("status", "workerId", "transportType", "endpointUri"))
                || !transport.name().equals(fields.get("transportType"))
                || !(fields.get("endpointUri") instanceof String)) {
            throw new IllegalArgumentException("Invalid prepared response");
        }
        return prepared(new PreparedWorker(workerId, URI.create((String) fields.get("endpointUri"))));
    }
}
