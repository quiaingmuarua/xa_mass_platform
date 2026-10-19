package com.xa.mass.server.credits;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class CreditAccountStore implements AutoCloseable {
    private static final String SCRIPT = script();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RedisClient redis;
    private final RedisKeyspace keyspace;
    private final Clock clock;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    @Autowired public CreditAccountStore(RedisClient redis, RedisKeyspace keyspace) {
        this(redis, keyspace, Clock.systemUTC());
    }
    CreditAccountStore(RedisClient redis, RedisKeyspace keyspace, Clock clock) {
        this.redis = redis; this.keyspace = keyspace; this.clock = clock;
    }
    record Account(boolean exists, long balance, long sequence, long updatedAtMillis) {}
    record Page(boolean exists, CreditTransactionsResponse response) {}
    record Observation(boolean exists, CreditAdjustmentResponse response) {}

    Account account(String userId) { return account(evaluate(userId, "account")); }
    Page transactions(String userId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw CreditModels.invalid("limit must be in 1..100");
        long before = decodeCursor(userId, cursor);
        List<String> values = evaluate(userId, "page", Long.toString(before), Integer.toString(limit));
        var rows = new ArrayList<CreditTransaction>();
        for (int i = 5; i < values.size(); i++) rows.add(transaction(values.get(i)));
        long start = Long.parseLong(values.get(4));
        long next = start - rows.size();
        String nextCursor = next < 1 ? null : Base64.getUrlEncoder().withoutPadding()
                .encodeToString((userId + ":" + next).getBytes(StandardCharsets.UTF_8));
        return new Page(account(values).exists(), new CreditTransactionsResponse(rows, nextCursor));
    }
    Observation adjustment(String userId, String requestId) {
        var values = evaluate(userId, "receipt", requestId);
        return new Observation(account(values).exists(), response(requestId, values));
    }
    CreditAdjustmentResponse adjust(String userId, String actor, Adjustment input) {
        return response(input.requestId(), evaluate(userId, "adjust", input.requestId(),
                CreditModels.fingerprint(actor, input), actor, Long.toString(input.delta()), input.reason(),
                Long.toString(clock.millis())));
    }
    /** Internal bookkeeping primitive. The application must authorize and durably bind its intent first. */
    public CreditAdjustmentResponse debit(String userId, String actor, Debit input) {
        var b = input.business();
        return response(input.requestId(), evaluate(userId, "debit", input.requestId(),
                CreditModels.fingerprint(actor, input), CreditModels.userId(actor), Long.toString(-b.amount()),
                b.source() + " task start", Long.toString(clock.millis()), b.source(), b.taskId(), b.checkId(),
                Long.toString(b.unitPrice()), Long.toString(b.quantity())));
    }
    public CreditAdjustmentResponse debitReceipt(String userId, String requestId) {
        return adjustment(userId, CreditModels.requestId(requestId)).response();
    }
    private List<String> evaluate(String userId, String mode, String... input) {
        var args = new ArrayList<String>(List.of(CreditModels.userId(userId), mode));
        args.addAll(List.of(input));
        try {
            List<?> rows = connection().sync().eval(SCRIPT, ScriptOutputType.MULTI, new String[]{key(userId)}, args.toArray(String[]::new));
            if (rows == null) throw new IllegalStateException("Credit operation has no response");
            var values = rows.stream().map(row -> (String) row).toList();
            if (values.equals(List.of("conflict")))
                throw new ServerException(ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "credits." + mode, null, null);
            return values;
        } catch (ServerException known) { throw known;
        } catch (RuntimeException failure) { throw unavailable(mode, failure); }
    }
    private static Account account(List<String> values) {
        return new Account(values.get(0).equals("1"), Long.parseLong(values.get(1)), Long.parseLong(values.get(2)), Long.parseLong(values.get(3)));
    }
    private static CreditTransaction transaction(String value) {
        try { return JSON.readValue(value, CreditTransaction.class); }
        catch (RuntimeException failure) { throw unavailable("decodeTransaction", failure); }
    }
    private static CreditAdjustmentResponse response(String requestId, List<String> values) {
        if (values.size() == 4) return CreditAdjustmentResponse.unobserved(requestId);
        try {
            var receipt = JSON.readTree(values.get(4));
            if (receipt.get("status").asText().equals("applied")) {
                var row = transaction(values.get(5));
                return new CreditAdjustmentResponse(requestId, "applied", row, null, row.balanceAfter());
            }
            return new CreditAdjustmentResponse(requestId, "rejected", null, receipt.get("rejectionCode").asText(), receipt.get("balance").longValue());
        } catch (RuntimeException failure) { throw unavailable("decodeReceipt", failure); }
    }
    private static long decodeCursor(String userId, String cursor) {
        if (cursor == null) return 0;
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 2 || !parts[0].equals(userId) || !parts[1].matches("[1-9][0-9]{0,15}")) throw new IllegalArgumentException();
            long value = Long.parseLong(parts[1]);
            if (value > CreditModels.MAX_POINTS) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException failure) { throw CreditModels.invalid("Invalid credit cursor"); }
    }
    String key(String userId) { return keyspace.base() + ":server:credits:account:" + CreditModels.userId(userId); }
    private synchronized StatefulRedisConnection<String, String> connection() {
        if (closed) throw new IllegalStateException("Credit store is closed");
        if (connection == null || !connection.isOpen()) connection = redis.connect();
        return connection;
    }
    @Override public synchronized void close() { closed = true; if (connection != null) connection.close(); }
    private static ServerException unavailable(String operation, Throwable cause) {
        return new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "credits." + operation, null, cause);
    }
    private static String script() {
        try (var input = CreditAccountStore.class.getResourceAsStream("/credits/account.lua")) {
            if (input == null) throw new IllegalStateException("Missing credit account script");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
