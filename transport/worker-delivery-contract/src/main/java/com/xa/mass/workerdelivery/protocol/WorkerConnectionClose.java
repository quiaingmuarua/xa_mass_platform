package com.xa.mass.workerdelivery.protocol;

import com.xa.mass.workerdelivery.json.Jsons;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Payload of the Adapter's terminal connection command, not a business result. */
public final class WorkerConnectionClose {
    private final Long cooldownUntilMillis;

    private WorkerConnectionClose(Long cooldownUntilMillis) {
        this.cooldownUntilMillis = cooldownUntilMillis;
    }

    public static WorkerConnectionClose cooldown(long untilMillis) {
        if (untilMillis <= 0) throw new IllegalArgumentException("cooldownUntilMillis must be positive");
        return new WorkerConnectionClose(untilMillis);
    }

    public Long cooldownUntilMillis() {
        return cooldownUntilMillis;
    }

    public String encode() {
        return cooldownUntilMillis == null ? "null" : Jsons.toJson(Map.of(
                "reason", "cooldown", "cooldownUntilMillis", cooldownUntilMillis));
    }

    public static WorkerConnectionClose decode(String encoded) {
        if (encoded != null && "null".equals(encoded.trim())) return new WorkerConnectionClose(null);
        Map<?, ?> fields = Jsons.parseObject(encoded);
        if (!fields.keySet().equals(Set.of("reason", "cooldownUntilMillis"))
                || !"cooldown".equals(fields.get("reason"))
                || !(fields.get("cooldownUntilMillis") instanceof Number)) {
            throw new IllegalArgumentException("Invalid connection close payload");
        }
        try {
            return cooldown(new BigDecimal(fields.get("cooldownUntilMillis").toString()).longValueExact());
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid cooldown deadline", invalid);
        }
    }
}
