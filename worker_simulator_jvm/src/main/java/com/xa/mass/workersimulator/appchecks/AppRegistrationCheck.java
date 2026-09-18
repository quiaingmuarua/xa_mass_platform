package com.xa.mass.workersimulator.appchecks;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** One synchronous simulated lookup. No business state, retry or retained Reporter. */
public final class AppRegistrationCheck {
    public static final String EVENT = "extension.worker.app.registration.check";
    private static final String OPERATION = "appRegistrationCheck.execute";

    private AppRegistrationCheck() {}

    public static WorkerEventDefinition<Map<String, Object>> definition(String group, Supplier<String> workerId) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(workerId, "workerId");
        return WorkerEventDefinition.extension("app.registration.check", WorkerEventParameterResolvers.jsonMap(),
                input -> execute(group, workerId.get(), input));
    }

    static String execute(String group, String workerId, Map<String, Object> input) {
        if (workerId == null || workerId.isBlank())
            throw failure("Executing Worker identity is unavailable", null);
        final Plan plan;
        final String number;
        try {
            if (input == null || !input.keySet().equals(Set.of("number", "salt", "ranges", "delayMs")))
                throw new IllegalArgumentException("Expected number, salt, ranges and delayMs");
            number = text(input.get("number"), 16);
            if (!number.matches("\\+[1-9][0-9]{1,14}")) throw new IllegalArgumentException("Invalid international number");
            plan = plan(workerId, text(input.get("salt"), 128), number, input.get("ranges"), input.get("delayMs"));
        } catch (IllegalArgumentException invalid) {
            throw new WorkerException(WorkerErrorCode.EVENT_INPUT_INVALID, "appRegistrationCheck.resolve",
                    "Invalid application check input", invalid);
        }
        try {
            Thread.sleep(plan.delayMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("Application check was interrupted", interrupted);
        }
        if (plan.outcome().equals("failed")) throw failure("Simulated application check failure", null);
        return Jsons.toJson(Map.of("number", number, "registered", plan.outcome().equals("registered"),
                "workerId", workerId, "workerGroupId", group, "simulatedDelayMillis", plan.delayMillis()));
    }

    static Plan plan(String workerId, String salt, String number, Object rangesValue, Object delayValue) {
        if (!(rangesValue instanceof Map<?, ?> ranges)
                || !ranges.keySet().equals(Set.of("registered", "unregistered", "failed")))
            throw new IllegalArgumentException("Expected three ranges");
        long[] delay = range(delayValue, 30_000);
        if (Jsons.toJson(Map.of("ranges", ranges, "delayMs", delayValue)).length() > 4096)
            throw new IllegalArgumentException("Description is too large");
        boolean[] covered = new boolean[1000];
        long bucket = Long.remainderUnsigned(hash("outcome", workerId, salt, number), 1000);
        String outcome = null;
        for (String key : List.of("registered", "unregistered", "failed")) {
            long[] range = range(ranges.get(key), 1000);
            for (int i = (int) range[0]; i < range[1]; i++) {
                if (covered[i]) throw new IllegalArgumentException("Ranges overlap");
                covered[i] = true;
            }
            if (range[0] <= bucket && bucket < range[1]) outcome = key;
        }
        for (boolean present : covered) if (!present) throw new IllegalArgumentException("Ranges have a gap");
        long millis = delay[0] + Long.remainderUnsigned(hash("delay", workerId, salt, number), delay[1] - delay[0] + 1);
        return new Plan(outcome, bucket, millis);
    }

    static long hash(String domain, String workerId, String salt, String number) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of("app-checks/v1/" + domain, workerId, salt, number)) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static long[] range(Object value, long maximum) {
        if (!(value instanceof List<?> bounds) || bounds.size() != 2)
            throw new IllegalArgumentException("Expected two integer bounds");
        long start = integer(bounds.get(0)), end = integer(bounds.get(1));
        if (start < 0 || start > end || end > maximum) throw new IllegalArgumentException("Invalid bounds");
        return new long[]{start, end};
    }

    private static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof BigDecimal decimal) {
            try { return decimal.longValueExact(); } catch (ArithmeticException invalid) { /* rejected below */ }
        }
        throw new IllegalArgumentException("Expected an integer");
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum)
            throw new IllegalArgumentException("Invalid text");
        return text;
    }

    private static WorkerException failure(String message, Throwable cause) {
        return new WorkerException(WorkerErrorCode.EVENT_EXECUTION_FAILED, OPERATION, message, cause);
    }

    record Plan(String outcome, long bucket, long delayMillis) {}
}
