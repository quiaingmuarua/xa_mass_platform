package com.xa.mass.server.credits;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.directory.TaskUserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Account facts only; business callers supply immutable charge attribution, never pricing policy. */
public final class CreditModels {
    public static final long MAX_POINTS = 9_007_199_254_740_991L;
    private CreditModels() {}

    public record CreditAccountResponse(String userId, long balance, long latestSequence,
                                        @Nullable Long updatedAtMillis, boolean userPresent) {}
    public record CreditTransaction(long sequence, String requestId, String userId, String actorUserId,
                                    long delta, long balanceBefore, long balanceAfter, String reason,
                                    long createdAtMillis, @Nullable BusinessCharge business) {}
    public record BusinessCharge(String source, String taskId, String checkId, long unitPrice, long quantity) {
        public BusinessCharge {
            if (source == null || !source.matches("[a-z][a-z0-9-]{0,63}") || taskId == null || taskId.isBlank()
                    || taskId.length() > 256 || checkId == null || checkId.isBlank() || checkId.length() > 128
                    || unitPrice < 1 || unitPrice > MAX_POINTS || quantity < 1 || quantity > MAX_POINTS / unitPrice)
                throw invalid("Invalid business charge or amount exceeds safe-integer limit");
        }
        public long amount() { return unitPrice * quantity; }
    }
    public record Debit(String requestId, BusinessCharge business) {
        public Debit { requestId = CreditModels.requestId(requestId); java.util.Objects.requireNonNull(business); }
    }
    public record CreditTransactionsResponse(List<CreditTransaction> entries, @Nullable String nextCursor) {
        public CreditTransactionsResponse { entries = List.copyOf(entries); }
    }
    public record CreditAdjustmentResponse(String requestId, String status,
            @Nullable CreditTransaction transaction, @Nullable String rejectionCode,
            @Nullable Long balanceAtDecision) {
        public static CreditAdjustmentResponse unobserved(String requestId) {
            return new CreditAdjustmentResponse(requestId, "not_observed", null, null, null);
        }
    }
    public record Adjustment(String requestId, long delta, String reason) {
        public Adjustment {
            requestId = CreditModels.requestId(requestId);
            if (delta == 0 || delta < -MAX_POINTS || delta > MAX_POINTS) throw invalid("delta must be a nonzero safe integer");
            if (reason == null || (reason = reason.strip()).isEmpty() || reason.length() > 500)
                throw invalid("reason must contain 1..500 characters");
        }
    }

    public static String userId(String value) {
        try { return TaskUserId.normalize(value); }
        catch (RuntimeException invalid) { throw invalid("Invalid userId"); }
    }
    public static String requestId(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) { throw invalid("requestId must be a canonical UUID"); }
    }
    static String fingerprint(String actor, Adjustment adjustment) {
        return digest(List.of(actor, Long.toString(adjustment.delta()), adjustment.reason()));
    }
    static String fingerprint(String actor, Debit debit) {
        var b = debit.business();
        return digest(List.of("business-debit", actor, b.source(), b.taskId(), b.checkId(),
                Long.toString(b.unitPrice()), Long.toString(b.quantity())));
    }
    private static String digest(List<String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ServerException invalid(String message) {
        return new ServerException(ServerErrorCode.INVALID_CREDIT_REQUEST, "credits.validate", message, null);
    }
}
