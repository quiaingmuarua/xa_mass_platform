package com.xa.mass.server.credits;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.credits.CreditAccountValidator.StoredReceipt;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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

    @Autowired
    public CreditAccountStore(RedisClient redis, RedisKeyspace keyspace) {
        this(redis, keyspace, Clock.systemUTC());
    }

    CreditAccountStore(RedisClient redis, RedisKeyspace keyspace, Clock clock) {
        this.redis = redis;
        this.keyspace = keyspace;
        this.clock = clock;
    }

    record Account(boolean exists, long balance, long sequence, long updatedAtMillis) {}
    record Page(boolean exists, CreditTransactionsResponse response) {}
    record Observation(boolean exists, CreditAdjustmentResponse response) {}

    Account account(String userId) {
        String normalizedUser = CreditModels.userId(userId);
        String accountKey = key(normalizedUser);
        try {
            var redisCommands = connection().sync();
            Account meta = loadAccountMeta(redisCommands, accountKey);
            if (meta.sequence() > 0) {
                validateHead(redisCommands, accountKey, normalizedUser, meta);
            }
            return meta;
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("account", failure);
        }
    }

    Page transactions(String userId, String cursor, int limit) {
        String normalizedUser = CreditModels.userId(userId);
        if (limit < 1 || limit > 100) {
            throw CreditModels.invalid("limit must be in 1..100");
        }
        long before = decodeCursor(normalizedUser, cursor);
        String accountKey = key(normalizedUser);
        try {
            var redisCommands = connection().sync();
            Account meta = loadAccountMeta(redisCommands, accountKey);
            if (!meta.exists() || meta.sequence() == 0) {
                return new Page(meta.exists(), new CreditTransactionsResponse(List.of(), null));
            }
            long start = before == 0 ? meta.sequence() : Math.min(before, meta.sequence());
            long end = Math.max(1, start - limit + 1);
            var txFields = new ArrayList<String>((int) (start - end + 2));
            for (long seq = start; seq >= end; seq--) {
                txFields.add("transaction:" + seq);
            }
            boolean extraHead = meta.sequence() > start;
            if (extraHead) {
                txFields.add("transaction:" + meta.sequence());
            }
            Map<String, String> rawTxByField = readFields(redisCommands, accountKey, txFields);

            var rows = new ArrayList<CreditTransaction>((int) (start - end + 1));
            var toVerify = new ArrayList<CreditTransaction>((int) (start - end + 2));
            CreditTransaction previous = null;
            for (long seq = start; seq >= end; seq--) {
                CreditTransaction tx = CreditAccountValidator.decodeTransaction(
                        rawTxByField.get("transaction:" + seq), normalizedUser, seq);
                if (previous != null && previous.balanceBefore() != tx.balanceAfter()) {
                    throw CreditAccountValidator.corrupt();
                }
                rows.add(tx);
                toVerify.add(tx);
                previous = tx;
            }
            CreditTransaction head = extraHead
                    ? CreditAccountValidator.decodeTransaction(
                            rawTxByField.get("transaction:" + meta.sequence()), normalizedUser, meta.sequence())
                    : rows.getFirst();
            if (head.balanceAfter() != meta.balance() || head.createdAtMillis() != meta.updatedAtMillis()) {
                throw CreditAccountValidator.corrupt();
            }
            if (extraHead) {
                toVerify.add(head);
            }
            verifyTransactionLinks(redisCommands, accountKey, meta.sequence(), toVerify);

            long next = start - rows.size();
            String nextCursor = next < 1 ? null : Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((normalizedUser + ":" + next).getBytes(StandardCharsets.UTF_8));
            return new Page(true, new CreditTransactionsResponse(rows, nextCursor));
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("page", failure);
        }
    }

    Observation adjustment(String userId, String requestId) {
        String normalizedUser = CreditModels.userId(userId);
        String normalizedRequest = CreditModels.requestId(requestId);
        String accountKey = key(normalizedUser);
        try {
            var redisCommands = connection().sync();
            String requestField = "request:" + normalizedRequest;
            List<KeyValue<String, String>> initial = redisCommands.hmget(
                    accountKey, "balance", "sequence", "updatedAtMillis", requestField);
            if (!anyMetaPresent(initial)) {
                if (redisCommands.exists(accountKey) == 0) {
                    return new Observation(false, CreditAdjustmentResponse.unobserved(normalizedRequest));
                }
                initial = redisCommands.hmget(accountKey, "balance", "sequence", "updatedAtMillis", requestField);
                if (!anyMetaPresent(initial)) {
                    throw CreditAccountValidator.corrupt();
                }
            }
            Account meta = parseExistingMeta(initial);
            String rawReceipt = initial.get(3).getValueOrElse(null);
            StoredReceipt receipt = rawReceipt == null
                    ? null
                    : CreditAccountValidator.decodeReceipt(rawReceipt, meta.sequence());

            var txFields = new LinkedHashSet<String>();
            if (meta.sequence() > 0) {
                txFields.add("transaction:" + meta.sequence());
            }
            if (receipt != null && receipt.isApplied()) {
                txFields.add("transaction:" + receipt.sequence());
            }
            Map<String, String> rawTx = txFields.isEmpty()
                    ? Map.of()
                    : readFields(redisCommands, accountKey, List.copyOf(txFields));
            var toVerify = new ArrayList<CreditTransaction>(2);
            if (meta.sequence() > 0) {
                CreditTransaction head = CreditAccountValidator.decodeTransaction(
                        rawTx.get("transaction:" + meta.sequence()), normalizedUser, meta.sequence());
                if (head.balanceAfter() != meta.balance() || head.createdAtMillis() != meta.updatedAtMillis()) {
                    throw CreditAccountValidator.corrupt();
                }
                toVerify.add(head);
            }
            CreditTransaction requestedTx = null;
            if (receipt != null && receipt.isApplied()) {
                requestedTx = CreditAccountValidator.decodeTransaction(
                        rawTx.get("transaction:" + receipt.sequence()), normalizedUser, receipt.sequence());
                if (!requestedTx.requestId().equals(normalizedRequest)) {
                    throw CreditAccountValidator.corrupt();
                }
                if (toVerify.isEmpty() || toVerify.getFirst().sequence() != requestedTx.sequence()) {
                    toVerify.add(requestedTx);
                }
            }
            if (!toVerify.isEmpty()) {
                verifyTransactionLinks(redisCommands, accountKey, meta.sequence(), toVerify);
            }
            if (receipt == null) {
                return new Observation(true, CreditAdjustmentResponse.unobserved(normalizedRequest));
            }
            return new Observation(true, toResponse(normalizedRequest, receipt, requestedTx));
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable("receipt", failure);
        }
    }

    CreditAdjustmentResponse adjust(String userId, String actor, Adjustment input) {
        String normalizedUser = CreditModels.userId(userId);
        String normalizedActor = CreditModels.userId(actor);
        String fingerprint = CreditModels.fingerprint(normalizedActor, input);
        return commit("adjust", normalizedUser, normalizedActor, input.requestId(), fingerprint,
                input.delta(), input.reason(), null);
    }

    /** Internal bookkeeping primitive. The application must authorize and durably bind its intent first. */
    public CreditAdjustmentResponse debit(String userId, String actor, Debit input) {
        String normalizedUser = CreditModels.userId(userId);
        String normalizedActor = CreditModels.userId(actor);
        BusinessCharge business = input.business();
        String fingerprint = CreditModels.fingerprint(normalizedActor, input);
        return commit("debit", normalizedUser, normalizedActor, input.requestId(), fingerprint,
                -business.amount(), business.source() + " task start", business);
    }

    public CreditAdjustmentResponse debitReceipt(String userId, String requestId) {
        return adjustment(userId, CreditModels.requestId(requestId)).response();
    }

    private CreditAdjustmentResponse commit(
            String operation,
            String userId,
            String actor,
            String requestId,
            String fingerprint,
            long delta,
            String reason,
            @Nullable BusinessCharge business
    ) {
        long now = clock.millis();
        if (now < 0 || now > CreditModels.MAX_POINTS) {
            throw unavailable(operation, new IllegalStateException("Clock timestamp out of safe-integer range"));
        }
        String accountKey = key(userId);
        String businessKey = business == null ? "" : CreditAccountValidator.businessField(business);
        String rowTailJson = encodeRowTail(requestId, userId, actor, delta, reason, now, business);
        String appliedPrefix = "{\"fingerprint\":\"" + fingerprint + "\",\"status\":\"applied\",\"sequence\":";
        String rejectedPrefix = "{\"fingerprint\":\"" + fingerprint + "\",\"status\":\"rejected\",\"rejectionCode\":\"";
        try {
            var redisCommands = connection().sync();
            List<?> rows = redisCommands.eval(
                    SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{accountKey},
                    userId, requestId, Long.toString(delta), Long.toString(now),
                    businessKey, rowTailJson, appliedPrefix, rejectedPrefix);
            if (rows == null || rows.isEmpty()) {
                throw new IllegalStateException("Credit operation has no response");
            }
            List<String> values = rows.stream().map(row -> (String) row).toList();
            String outcome = values.getFirst();
            if ("committed".equals(outcome)) {
                String rawReceipt = values.get(1);
                String rawTx = values.get(2);
                StoredReceipt receipt = CreditAccountValidator.decodeReceipt(rawReceipt, CreditModels.MAX_POINTS);
                CreditTransaction tx = receipt.isApplied()
                        ? CreditAccountValidator.decodeTransaction(rawTx, userId, receipt.sequence())
                        : null;
                return toResponse(requestId, receipt, tx);
            }
            if ("existing".equals(outcome)) {
                long sequence = CreditAccountValidator.parseNonNegativeSafeInt(values.get(1));
                StoredReceipt receipt = CreditAccountValidator.decodeReceipt(values.get(2), sequence);
                if (!receipt.fingerprint().equals(fingerprint)) {
                    throw new ServerException(
                            ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "credits." + operation, null, null);
                }
                CreditTransaction tx = null;
                if (receipt.isApplied()) {
                    tx = CreditAccountValidator.decodeTransaction(values.get(3), userId, receipt.sequence());
                    if (!tx.requestId().equals(requestId)
                            || !tx.actorUserId().equals(actor)
                            || tx.delta() != delta
                            || !tx.reason().equals(reason)) {
                        throw CreditAccountValidator.corrupt();
                    }
                    if (tx.business() != null) {
                        String linked = redisCommands.hget(accountKey, CreditAccountValidator.businessField(tx.business()));
                        if (!requestId.equals(linked)) {
                            throw CreditAccountValidator.corrupt();
                        }
                    }
                }
                return toResponse(requestId, receipt, tx);
            }
            if ("business_exists".equals(outcome) && business != null) {
                long sequence = CreditAccountValidator.parseNonNegativeSafeInt(values.get(1));
                String linkedRequestId = CreditAccountValidator.requireUuid(values.get(2));
                String rawLinkedReceipt = redisCommands.hget(accountKey, "request:" + linkedRequestId);
                StoredReceipt linkedReceipt = CreditAccountValidator.decodeReceipt(rawLinkedReceipt, sequence);
                if (!linkedReceipt.isApplied()) {
                    throw CreditAccountValidator.corrupt();
                }
                String rawLinkedTx = redisCommands.hget(accountKey, "transaction:" + linkedReceipt.sequence());
                CreditTransaction linkedTx = CreditAccountValidator.decodeTransaction(
                        rawLinkedTx, userId, linkedReceipt.sequence());
                if (linkedTx.business() == null
                        || !linkedTx.business().source().equals(business.source())
                        || !linkedTx.business().taskId().equals(business.taskId())
                        || !linkedTx.requestId().equals(linkedRequestId)) {
                    throw CreditAccountValidator.corrupt();
                }
                throw new ServerException(
                        ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "credits." + operation, null, null);
            }
            throw new IllegalStateException("Unexpected credit script outcome: " + outcome);
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable(operation, failure);
        }
    }

    private static Account loadAccountMeta(RedisCommands<String, String> redisCommands, String accountKey) {
        List<KeyValue<String, String>> meta = redisCommands.hmget(accountKey, "balance", "sequence", "updatedAtMillis");
        if (!anyMetaPresent(meta)) {
            if (redisCommands.exists(accountKey) == 0) {
                return new Account(false, 0, 0, 0);
            }
            // Key appeared concurrently or is a corrupt HASH missing metadata fields; re-read once.
            meta = redisCommands.hmget(accountKey, "balance", "sequence", "updatedAtMillis");
            if (!anyMetaPresent(meta)) {
                throw CreditAccountValidator.corrupt();
            }
        }
        return parseExistingMeta(meta);
    }

    private static boolean anyMetaPresent(List<KeyValue<String, String>> fields) {
        return fields.get(0).hasValue() || fields.get(1).hasValue() || fields.get(2).hasValue();
    }

    private static Account parseExistingMeta(List<KeyValue<String, String>> meta) {
        long balance = CreditAccountValidator.parseNonNegativeSafeInt(meta.get(0).getValueOrElse(null));
        long sequence = CreditAccountValidator.parseNonNegativeSafeInt(meta.get(1).getValueOrElse(null));
        long updated = CreditAccountValidator.parseNonNegativeSafeInt(meta.get(2).getValueOrElse(null));
        if (sequence == 0 && (balance != 0 || updated != 0)) {
            throw CreditAccountValidator.corrupt();
        }
        return new Account(true, balance, sequence, updated);
    }

    private static void validateHead(
            RedisCommands<String, String> redisCommands,
            String accountKey,
            String userId,
            Account meta
    ) {
        String rawHead = redisCommands.hget(accountKey, "transaction:" + meta.sequence());
        CreditTransaction head = CreditAccountValidator.decodeTransaction(rawHead, userId, meta.sequence());
        if (head.balanceAfter() != meta.balance() || head.createdAtMillis() != meta.updatedAtMillis()) {
            throw CreditAccountValidator.corrupt();
        }
        verifyTransactionLinks(redisCommands, accountKey, meta.sequence(), List.of(head));
    }

    private static void verifyTransactionLinks(
            RedisCommands<String, String> redisCommands,
            String accountKey,
            long maxSequence,
            List<CreditTransaction> transactions
    ) {
        var fields = new LinkedHashSet<String>(transactions.size() * 2);
        for (CreditTransaction tx : transactions) {
            fields.add("request:" + tx.requestId());
            if (tx.business() != null) {
                fields.add(CreditAccountValidator.businessField(tx.business()));
            }
        }
        Map<String, String> loaded = readFields(redisCommands, accountKey, List.copyOf(fields));
        for (CreditTransaction tx : transactions) {
            StoredReceipt receipt = CreditAccountValidator.decodeReceipt(
                    loaded.get("request:" + tx.requestId()), maxSequence);
            if (!receipt.isApplied() || receipt.sequence() != tx.sequence()) {
                throw CreditAccountValidator.corrupt();
            }
            if (tx.business() != null) {
                String linked = loaded.get(CreditAccountValidator.businessField(tx.business()));
                if (!tx.requestId().equals(linked)) {
                    throw CreditAccountValidator.corrupt();
                }
            }
        }
    }

    private static Map<String, String> readFields(
            RedisCommands<String, String> redisCommands,
            String accountKey,
            List<String> fields
    ) {
        var result = new LinkedHashMap<String, String>(fields.size());
        for (KeyValue<String, String> entry : redisCommands.hmget(accountKey, fields.toArray(String[]::new))) {
            if (entry.hasValue()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static CreditAdjustmentResponse toResponse(
            String requestId,
            StoredReceipt receipt,
            @Nullable CreditTransaction transaction
    ) {
        if (receipt.isApplied()) {
            return new CreditAdjustmentResponse(requestId, "applied", transaction, null, transaction.balanceAfter());
        }
        return new CreditAdjustmentResponse(requestId, "rejected", null, receipt.rejectionCode(), receipt.balance());
    }

    private static String encodeRowTail(
            String requestId,
            String userId,
            String actor,
            long delta,
            String reason,
            long now,
            @Nullable BusinessCharge business
    ) {
        var tail = new LinkedHashMap<String, Object>();
        tail.put("requestId", requestId);
        tail.put("userId", userId);
        tail.put("actorUserId", actor);
        tail.put("delta", delta);
        tail.put("reason", reason);
        tail.put("createdAtMillis", now);
        if (business != null) {
            tail.put("business", business);
        }
        String json = JSON.writeValueAsString(tail);
        return "," + json.substring(1);
    }

    private static long decodeCursor(String userId, String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 2 || !parts[0].equals(userId) || !parts[1].matches("[1-9][0-9]{0,15}")) {
                throw new IllegalArgumentException();
            }
            long value = Long.parseLong(parts[1]);
            if (value > CreditModels.MAX_POINTS) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw CreditModels.invalid("Invalid credit cursor");
        }
    }

    String key(String userId) {
        return keyspace.base() + ":server:credits:account:" + CreditModels.userId(userId);
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (closed) {
            throw new IllegalStateException("Credit store is closed");
        }
        if (connection == null || !connection.isOpen()) {
            connection = redis.connect();
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (connection != null) {
            connection.close();
        }
    }

    private static ServerException unavailable(String operation, Throwable cause) {
        return new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "credits." + operation, null, cause);
    }

    private static String script() {
        try (var input = CreditAccountStore.class.getResourceAsStream("/credits/account.lua")) {
            if (input == null) {
                throw new IllegalStateException("Missing credit account script");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
