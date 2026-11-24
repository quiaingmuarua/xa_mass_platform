package com.xa.mass.server.credits;

import static com.xa.mass.server.credits.CreditModels.MAX_POINTS;

import com.xa.mass.server.credits.CreditModels.BusinessCharge;
import com.xa.mass.server.credits.CreditModels.CreditTransaction;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict Java schema and invariant validation for stored credit account records. */
final class CreditAccountValidator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SAFE_INT = Pattern.compile("0|[1-9][0-9]{0,15}");
    private static final Pattern USER_ID = Pattern.compile("[1-9a-z][0-9a-z]{3,8}");
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BUSINESS_SOURCE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Set<String> REJECTION_CODES = Set.of(
            "insufficient_balance", "balance_limit", "sequence_limit");
    private static final Set<String> TX_FIELDS = Set.of(
            "sequence", "requestId", "userId", "actorUserId", "delta",
            "balanceBefore", "balanceAfter", "reason", "createdAtMillis");
    private static final Set<String> TX_BUSINESS_FIELDS = Set.of(
            "sequence", "requestId", "userId", "actorUserId", "delta",
            "balanceBefore", "balanceAfter", "reason", "createdAtMillis", "business");
    private static final Set<String> BUSINESS_FIELDS = Set.of(
            "source", "taskId", "checkId", "unitPrice", "quantity");
    private static final Set<String> APPLIED_RECEIPT_FIELDS = Set.of("fingerprint", "status", "sequence");
    private static final Set<String> REJECTED_RECEIPT_FIELDS = Set.of(
            "fingerprint", "status", "rejectionCode", "balance");

    private CreditAccountValidator() {
    }

    record StoredReceipt(
            String fingerprint,
            String status,
            @Nullable Long sequence,
            @Nullable String rejectionCode,
            @Nullable Long balance
    ) {
        boolean isApplied() {
            return "applied".equals(status);
        }
    }

    static long parseNonNegativeSafeInt(@Nullable String raw) {
        if (raw == null || !SAFE_INT.matcher(raw).matches()) {
            throw corrupt();
        }
        long value = Long.parseLong(raw);
        if (value < 0 || value > MAX_POINTS) {
            throw corrupt();
        }
        return value;
    }

    static String requireUuid(@Nullable String raw) {
        if (raw == null || raw.length() != 36) {
            throw corrupt();
        }
        try {
            if (!UUID.fromString(raw).toString().equals(raw)) {
                throw corrupt();
            }
            return raw;
        } catch (IllegalArgumentException invalid) {
            throw corrupt();
        }
    }

    static CreditTransaction decodeTransaction(
            @Nullable String raw,
            String expectedUserId,
            long expectedSequence
    ) {
        JsonNode node = parseObject(raw);
        boolean hasBusiness = node.has("business");
        Set<String> expectedFields = hasBusiness ? TX_BUSINESS_FIELDS : TX_FIELDS;
        if (!node.propertyNames().equals(expectedFields)) {
            throw corrupt();
        }
        long sequence = requireInt(node, "sequence", 1, MAX_POINTS);
        if (sequence != expectedSequence) {
            throw corrupt();
        }
        String userId = requireText(node, "userId");
        if (!userId.equals(expectedUserId)) {
            throw corrupt();
        }
        String requestId = requireUuid(requireText(node, "requestId"));
        String actorUserId = requireText(node, "actorUserId");
        if (!USER_ID.matcher(actorUserId).matches()) {
            throw corrupt();
        }
        long delta = requireInt(node, "delta", -MAX_POINTS, MAX_POINTS);
        if (delta == 0) {
            throw corrupt();
        }
        long balanceBefore = requireInt(node, "balanceBefore", 0, MAX_POINTS);
        long balanceAfter = requireInt(node, "balanceAfter", 0, MAX_POINTS);
        if ((delta > 0 && balanceBefore > MAX_POINTS - delta)
                || (delta < 0 && balanceBefore < -delta)
                || balanceBefore + delta != balanceAfter) {
            throw corrupt();
        }
        String reason = requireText(node, "reason");
        if (reason.isEmpty()) {
            throw corrupt();
        }
        long createdAtMillis = requireInt(node, "createdAtMillis", 0, MAX_POINTS);
        BusinessCharge business = null;
        if (hasBusiness) {
            JsonNode b = node.get("business");
            if (b == null || !b.isObject() || !b.propertyNames().equals(BUSINESS_FIELDS)) {
                throw corrupt();
            }
            String source = requireText(b, "source");
            String taskId = requireText(b, "taskId");
            String checkId = requireText(b, "checkId");
            if (!BUSINESS_SOURCE.matcher(source).matches()
                    || taskId.isEmpty() || taskId.length() > 256
                    || checkId.isEmpty() || checkId.length() > 128) {
                throw corrupt();
            }
            long unitPrice = requireInt(b, "unitPrice", 1, MAX_POINTS);
            long quantity = requireInt(b, "quantity", 1, MAX_POINTS / unitPrice);
            if (delta != -(unitPrice * quantity)) {
                throw corrupt();
            }
            try {
                business = new BusinessCharge(source, taskId, checkId, unitPrice, quantity);
            } catch (RuntimeException invalid) {
                throw corrupt();
            }
        }
        return new CreditTransaction(
                sequence, requestId, userId, actorUserId, delta,
                balanceBefore, balanceAfter, reason, createdAtMillis, business);
    }

    static StoredReceipt decodeReceipt(@Nullable String raw, long maxSequence) {
        JsonNode node = parseObject(raw);
        String fingerprint = requireText(node, "fingerprint");
        if (!HEX_64.matcher(fingerprint).matches()) {
            throw corrupt();
        }
        String status = requireText(node, "status");
        if ("applied".equals(status)) {
            if (!node.propertyNames().equals(APPLIED_RECEIPT_FIELDS) || maxSequence < 1) {
                throw corrupt();
            }
            long sequence = requireInt(node, "sequence", 1, maxSequence);
            return new StoredReceipt(fingerprint, status, sequence, null, null);
        }
        if ("rejected".equals(status)) {
            if (!node.propertyNames().equals(REJECTED_RECEIPT_FIELDS)) {
                throw corrupt();
            }
            String rejectionCode = requireText(node, "rejectionCode");
            if (!REJECTION_CODES.contains(rejectionCode)) {
                throw corrupt();
            }
            long balance = requireInt(node, "balance", 0, MAX_POINTS);
            return new StoredReceipt(fingerprint, status, null, rejectionCode, balance);
        }
        throw corrupt();
    }

    static String businessField(BusinessCharge business) {
        return businessField(business.source(), business.taskId());
    }

    static String businessField(String source, String taskId) {
        return "business:" + source + ":" + taskId;
    }

    static IllegalStateException corrupt() {
        return new IllegalStateException("Credit account storage is corrupt");
    }

    private static JsonNode parseObject(@Nullable String raw) {
        if (raw == null) {
            throw corrupt();
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) {
                throw corrupt();
            }
            return node;
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    private static long requireInt(JsonNode node, String field, long min, long max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        long number = value.longValue();
        if (number < min || number > max) {
            throw corrupt();
        }
        return number;
    }
}
