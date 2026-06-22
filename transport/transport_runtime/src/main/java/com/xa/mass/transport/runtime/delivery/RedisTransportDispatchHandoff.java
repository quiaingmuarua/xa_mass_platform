package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed non-blocking dispatch handoff.
 */
public final class RedisTransportDispatchHandoff implements TransportDispatchHandoff,
        AdapterMailboxConsumerRegistry,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DISPATCH;
    public static final int DEFAULT_MAX_QUEUED_ITEMS_PER_QUEUE = 100_000;
    private static final long DEFAULT_DISPATCH_RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);
    private static final long POLL_SLEEP_MILLIS = 50L;
    private static final String MEMBER_SEPARATOR = "\u001f";
    private static final String CONSUMER_EVIDENCE_VERSION = "v3";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();
    private static final String OFFER_DISPATCH_SCRIPT = """
            local dispatchStoreKey = KEYS[1]
            local dispatchRetentionDeadlineKey = KEYS[2]
            local readyDispatchKey = KEYS[3]
            local queuesKey = KEYS[4]
            local maxQueuedItems = tonumber(ARGV[1])
            local deliveryId = ARGV[2]
            local value = ARGV[3]
            local retentionDeadlineMillis = tonumber(ARGV[4])
            local adapterMailboxKey = ARGV[5]
            local referenceValue = ARGV[6]
            if maxQueuedItems <= 0 then
              return {'BACKPRESSURE', 'queue capacity is exhausted'}
            end
            local queuedItems = redis.call('HLEN', dispatchStoreKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE', 'dispatch queue backlog is full'}
            end
            if redis.call('HEXISTS', dispatchStoreKey, deliveryId) == 0 then
              redis.call('HSET', dispatchStoreKey, deliveryId, value)
              redis.call('ZADD', dispatchRetentionDeadlineKey, retentionDeadlineMillis, deliveryId)
            end
            redis.call('RPUSH', readyDispatchKey, referenceValue)
            redis.call('SADD', queuesKey, adapterMailboxKey)
            return {'QUEUED', ''}
            """;
    private static final String CLAIM_READY_REFERENCE_SCRIPT = """
            local readyDispatchKey = KEYS[1]
            local inflightDispatchKey = KEYS[2]
            local visibilityDeadlineMillis = tonumber(ARGV[1])
            local referenceValue = redis.call('LPOP', readyDispatchKey)
            if not referenceValue then
              return nil
            end
            redis.call('ZADD', inflightDispatchKey, visibilityDeadlineMillis, referenceValue)
            return referenceValue
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final Map<String, LocalMailboxConsumerEvidence> localConsumers = new ConcurrentHashMap<>();
    private final int maxQueuedItemsPerQueue;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportDispatchBatchCodec codec = new TransportDispatchBatchCodec();

    public RedisTransportDispatchHandoff(String redisUri,
                                         String namespacePrefix,
                                         int maxQueuedItemsPerQueue) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedItemsPerQueue,
                true
        );
    }

    RedisTransportDispatchHandoff(RedisClient redisClient,
                                  String namespacePrefix,
                                  int maxQueuedItemsPerQueue,
                                  boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                maxQueuedItemsPerQueue,
                ownsClient
        );
    }

    RedisTransportDispatchHandoff(StatefulRedisConnection<String, String> connection,
                                  String namespacePrefix,
                                  int maxQueuedItemsPerQueue) {
        this(null, connection, namespacePrefix, maxQueuedItemsPerQueue, false);
    }

    private RedisTransportDispatchHandoff(RedisClient redisClient,
                                          StatefulRedisConnection<String, String> connection,
                                          String namespacePrefix,
                                          int maxQueuedItemsPerQueue,
                                          boolean ownsClient) {
        if (maxQueuedItemsPerQueue <= 0) {
            throw new IllegalArgumentException("maxQueuedItemsPerQueue must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.maxQueuedItemsPerQueue = maxQueuedItemsPerQueue;
        this.ownsClient = ownsClient;
    }

    @Override
    public List<DispatchOutcome> offer(DispatchRoutingBatch batch) {
        Objects.requireNonNull(batch, "batch");
        String adapterMailboxKey = normalizeRequired(batch.adapterMailboxKey(), "adapterMailboxKey");
        List<DispatchRoutingItem> itemsToOffer = batch.items();
        if (!running.get()) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "dispatch handoff is stopped"))
                    .toList();
        }
        if (currentMailboxConsumerEvidence(adapterMailboxKey, System.currentTimeMillis()) == null) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "adapter mailbox has no active consumer"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(itemsToOffer.size());
        long now = System.currentTimeMillis();
        long retentionDeadline = now + DEFAULT_DISPATCH_RETENTION_MILLIS;
        for (DispatchRoutingItem item : itemsToOffer) {
            DispatchHandoffReference reference = new DispatchHandoffReference(
                    adapterMailboxKey,
                    item.deliveryId()
            );
            DispatchRoutingBatch itemBatch = new DispatchRoutingBatch(batch.target(), List.of(item));
            Object raw = commands.eval(
                    OFFER_DISPATCH_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            dispatchStoreKey(adapterMailboxKey),
                            dispatchRetentionDeadlineKey(adapterMailboxKey),
                            readyDispatchKey(adapterMailboxKey),
                            queuesKey()
                    },
                    Integer.toString(maxQueuedItemsPerQueue),
                    item.deliveryId(),
                    codec.encode(itemBatch),
                    Long.toString(retentionDeadline),
                    adapterMailboxKey,
                    encodeReference(reference)
            );
            List<?> values = raw instanceof List<?> list ? list : List.of();
            String status = values.isEmpty() ? "BACKPRESSURE" : String.valueOf(values.getFirst());
            String reason = values.size() > 1 ? String.valueOf(values.get(1)) : "dispatch offer failed";
            outcomes.add(DispatchOutcomeFactory.fromItem(
                    item,
                    "QUEUED".equals(status) ? DispatchOutcomeStatus.QUEUED : DispatchOutcomeStatus.BACKPRESSURE,
                    !"QUEUED".equals(status),
                    "QUEUED".equals(status) ? null : reason
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public ClaimedDispatchRoutingBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException {
        String mailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        if (!running.get()) {
            return null;
        }
        LocalMailboxConsumerEvidence local = localConsumers.get(mailboxKey);
        if (local == null || local.availableUntilEpochMillis() <= System.currentTimeMillis()) {
            if (local != null) {
                localConsumers.remove(mailboxKey, local);
            }
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            if (currentMailboxConsumerEvidence(mailboxKey, System.currentTimeMillis()) == null) {
                return null;
            }
            reclaimExpiredInflight(mailboxKey);
            String encodedReference = claimReadyReference(mailboxKey);
            if (encodedReference != null && !encodedReference.isBlank()) {
                ClaimedDispatchRoutingBatch claimed = materializeClaimedReference(mailboxKey, encodedReference);
                if (claimed != null) {
                    return claimed;
                }
            }
            if (timeoutMillis <= 0L) {
                return null;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return null;
            }
            Thread.sleep(Math.min(POLL_SLEEP_MILLIS, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        } while (running.get());
        return null;
    }

    private ClaimedDispatchRoutingBatch materializeClaimedReference(String claimedAdapterMailboxKey,
                                                                   String encodedReference) {
        DispatchHandoffReference reference = decodeReference(encodedReference);
        if (reference == null) {
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            return null;
        }
        String json = commands.hget(dispatchStoreKey(reference.adapterMailboxKey()), reference.deliveryId());
        if (json == null || json.isBlank()) {
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            return null;
        }
        DispatchRoutingBatch stored = codec.decode(json);
        LocalMailboxConsumerEvidence local = localConsumers.get(reference.adapterMailboxKey());
        if (local != null && local.availableUntilEpochMillis() <= System.currentTimeMillis()) {
            localConsumers.remove(local.adapterMailboxKey(), local);
            local = null;
        }
        MailboxConsumerEvidence currentConsumer = currentMailboxConsumerEvidence(
                reference.adapterMailboxKey(),
                System.currentTimeMillis()
        );
        if (currentConsumer == null
                || local == null
                || !local.consumerId().equals(currentConsumer.consumerId())) {
            if (local != null) {
                localConsumers.remove(local.adapterMailboxKey(), local);
            }
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            commands.rpush(readyDispatchKey(reference.adapterMailboxKey()), encodedReference);
            return null;
        }
        if (!reference.adapterMailboxKey().equals(stored.adapterMailboxKey())) {
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            return null;
        }
        return new ClaimedDispatchRoutingBatch(stored, List.of(reference));
    }

    @Override
    public void complete(ClaimedDispatchRoutingBatch batch, List<DispatchOutcome> outcomes) {
        if (batch == null || batch.references().isEmpty()) {
            return;
        }
        for (DispatchHandoffReference reference : batch.references()) {
            String encodedReference = encodeReference(reference);
            commands.zrem(inflightDispatchKey(reference.adapterMailboxKey()), encodedReference);
            commands.hdel(dispatchStoreKey(reference.adapterMailboxKey()), reference.deliveryId());
            commands.zrem(dispatchRetentionDeadlineKey(reference.adapterMailboxKey()), reference.deliveryId());
        }
    }

    @Override
    public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        LocalMailboxConsumerEvidence local = new LocalMailboxConsumerEvidence(
                lease.adapterMailboxKey(),
                lease.consumerId(),
                lease.generation(),
                lease.availableUntilEpochMillis()
        );
        localConsumers.put(lease.adapterMailboxKey(), local);
        commands.hset(mailboxConsumersKey(), lease.adapterMailboxKey(), encodeConsumerEvidence(lease));
        commands.zadd(mailboxConsumerDeadlinesKey(), lease.availableUntilEpochMillis(), lease.adapterMailboxKey());
        commands.sadd(queuesKey(), lease.adapterMailboxKey());
    }

    @Override
    public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        localConsumers.computeIfPresent(lease.adapterMailboxKey(),
                (ignored, current) -> lease.consumerId().equals(current.consumerId()) ? null : current);
        MailboxConsumerEvidence current = currentMailboxConsumerEvidence(lease.adapterMailboxKey(), System.currentTimeMillis());
        if (current == null || !lease.consumerId().equals(current.consumerId())) {
            return;
        }
        commands.hdel(mailboxConsumersKey(), lease.adapterMailboxKey());
        commands.zrem(mailboxConsumerDeadlinesKey(), lease.adapterMailboxKey());
    }

    @Override
    public void shutdown() {
        running.set(false);
        close();
    }

    @Override
    public void close() {
        if (connection.isOpen()) {
            connection.close();
        }
        if (ownsClient && redisClient != null) {
            redisClient.shutdown();
        }
    }

    int queuedBatches(String adapterMailboxKey) {
        return Math.toIntExact(commands.hlen(dispatchStoreKey(adapterMailboxKey)));
    }

    long readyReferencesForTest(String adapterMailboxKey) {
        return commands.llen(readyDispatchKey(adapterMailboxKey));
    }

    long inflightReferencesForTest(String adapterMailboxKey) {
        return commands.zcard(inflightDispatchKey(adapterMailboxKey));
    }

    void expireInflightForTest(String adapterMailboxKey) {
        String inflightKey = inflightDispatchKey(adapterMailboxKey);
        long expiredAt = System.currentTimeMillis() - 1L;
        for (String encodedReference : commands.zrange(inflightKey, 0, -1)) {
            commands.zadd(inflightKey, expiredAt, encodedReference);
        }
    }

    void deleteDispatchPayloadForTest(String adapterMailboxKey, String deliveryId) {
        commands.hdel(dispatchStoreKey(adapterMailboxKey), deliveryId);
        commands.zrem(dispatchRetentionDeadlineKey(adapterMailboxKey), deliveryId);
    }

    void pushReadyReferenceForTest(String adapterMailboxKey, String encodedReference) {
        commands.rpush(readyDispatchKey(adapterMailboxKey), encodedReference);
    }

    String encodeReferenceForTest(DispatchHandoffReference reference) {
        return encodeReference(reference);
    }

    void clearForTest(String adapterMailboxKey) {
        String normalizedMailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        commands.del(readyDispatchKey(normalizedMailboxKey));
        commands.del(inflightDispatchKey(normalizedMailboxKey));
        commands.del(dispatchStoreKey(normalizedMailboxKey));
        commands.del(dispatchRetentionDeadlineKey(normalizedMailboxKey));
        commands.hdel(mailboxConsumersKey(), normalizedMailboxKey);
        commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
        commands.srem(queuesKey(), normalizedMailboxKey);
    }

    void claimConsumerForTest(String adapterMailboxKey, String consumerId) {
        publishMailboxConsumerAvailability(new AdapterMailboxConsumerAvailability(
                adapterMailboxKey,
                consumerId,
                1L,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5L)
        ));
    }

    private String claimReadyReference(String adapterMailboxKey) {
        Object raw = commands.eval(
                CLAIM_READY_REFERENCE_SCRIPT,
                ScriptOutputType.VALUE,
                new String[]{
                        readyDispatchKey(adapterMailboxKey),
                        inflightDispatchKey(adapterMailboxKey)
                },
                Long.toString(System.currentTimeMillis() + DEFAULT_VISIBILITY_TIMEOUT_MILLIS)
        );
        return raw instanceof String value ? value : null;
    }

    private void removeInflightClaim(String adapterMailboxKey, String encodedReference) {
        commands.zrem(inflightDispatchKey(adapterMailboxKey), encodedReference);
    }

    private void reclaimExpiredInflight(String adapterMailboxKey) {
        long now = System.currentTimeMillis();
        List<String> expired = commands.zrangebyscore(
                inflightDispatchKey(adapterMailboxKey),
                Range.create(Double.NEGATIVE_INFINITY, (double) now)
        );
        for (String encodedReference : expired) {
            if (commands.zrem(inflightDispatchKey(adapterMailboxKey), encodedReference) > 0L) {
                DispatchHandoffReference reference = decodeReference(encodedReference);
                if (reference != null && commands.hexists(dispatchStoreKey(reference.adapterMailboxKey()), reference.deliveryId())) {
                    commands.rpush(readyDispatchKey(reference.adapterMailboxKey()), encodedReference);
                }
            }
        }
    }

    private MailboxConsumerEvidence currentMailboxConsumerEvidence(String adapterMailboxKey, long nowEpochMillis) {
        String normalizedMailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        MailboxConsumerEvidence evidence = decodeConsumerEvidence(
                commands.hget(mailboxConsumersKey(), normalizedMailboxKey)
        );
        if (evidence == null) {
            commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
            return null;
        }
        if (evidence.availableUntilEpochMillis() <= nowEpochMillis) {
            commands.hdel(mailboxConsumersKey(), normalizedMailboxKey);
            commands.zrem(mailboxConsumerDeadlinesKey(), normalizedMailboxKey);
            return null;
        }
        return evidence;
    }

    private String dispatchStoreKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":commands";
    }

    private String dispatchRetentionDeadlineKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":command-retention-deadlines";
    }

    private String readyDispatchKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":ready-commands";
    }

    private String inflightDispatchKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":inflight-commands";
    }

    private String mailboxConsumersKey() {
        return namespacePrefix + ":mailbox-consumers";
    }

    private String mailboxConsumerDeadlinesKey() {
        return namespacePrefix + ":mailbox-consumer-deadlines";
    }

    private String queuesKey() {
        return namespacePrefix + ":queues";
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value) {
        return new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String encodeReference(DispatchHandoffReference reference) {
        return encodeToken(reference.adapterMailboxKey())
                + MEMBER_SEPARATOR
                + encodeToken(reference.deliveryId());
    }

    private static DispatchHandoffReference decodeReference(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(MEMBER_SEPARATOR, -1);
        if (parts.length != 2) {
            return null;
        }
        return new DispatchHandoffReference(
                decodeToken(parts[0]),
                decodeToken(parts[1])
        );
    }

    private static String encodeConsumerEvidence(AdapterMailboxConsumerAvailability lease) {
        return String.join("|",
                CONSUMER_EVIDENCE_VERSION,
                encodeToken(lease.consumerId()),
                Long.toString(lease.generation()),
                Long.toString(lease.availableUntilEpochMillis())
        );
    }

    private static MailboxConsumerEvidence decodeConsumerEvidence(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4 || !CONSUMER_EVIDENCE_VERSION.equals(parts[0])) {
            return null;
        }
        return new MailboxConsumerEvidence(
                decodeToken(parts[1]),
                parseLong(parts[2]),
                parseLong(parts[3])
        );
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record MailboxConsumerEvidence(String consumerId,
                                           long generation,
                                           long availableUntilEpochMillis) {
    }

    private record LocalMailboxConsumerEvidence(String adapterMailboxKey,
                                                String consumerId,
                                                long generation,
                                                long availableUntilEpochMillis) {
    }
}
