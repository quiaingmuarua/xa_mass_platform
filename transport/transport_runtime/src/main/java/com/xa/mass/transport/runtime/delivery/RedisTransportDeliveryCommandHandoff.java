package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.DeliveryCommand;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed non-blocking delivery command handoff.
 */
public final class RedisTransportDeliveryCommandHandoff implements TransportDeliveryCommandHandoff,
        DeliveryCommandConsumerRegistry,
        AutoCloseable {

    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DELIVERY_COMMAND;
    public static final int DEFAULT_MAX_QUEUED_COMMANDS_PER_QUEUE = 100_000;
    private static final long DEFAULT_COMMAND_RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);
    private static final long POLL_SLEEP_MILLIS = 50L;
    private static final String MEMBER_SEPARATOR = "\u001f";
    private static final String CONSUMER_EVIDENCE_VERSION = "v3";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();
    private static final String OFFER_COMMAND_SCRIPT = """
            local commandStoreKey = KEYS[1]
            local commandRetentionDeadlineKey = KEYS[2]
            local readyCommandsKey = KEYS[3]
            local queuesKey = KEYS[4]
            local maxQueuedItems = tonumber(ARGV[1])
            local commandId = ARGV[2]
            local value = ARGV[3]
            local commandRetentionDeadlineMillis = tonumber(ARGV[4])
            local deliveryQueueKey = ARGV[5]
            local referenceValue = ARGV[6]
            if maxQueuedItems <= 0 then
              return {'BACKPRESSURE', 'queue capacity is exhausted'}
            end
            local queuedItems = redis.call('HLEN', commandStoreKey)
            if queuedItems >= maxQueuedItems then
              return {'BACKPRESSURE', 'delivery command queue backlog is full'}
            end
            if redis.call('HEXISTS', commandStoreKey, commandId) == 0 then
              redis.call('HSET', commandStoreKey, commandId, value)
              redis.call('ZADD', commandRetentionDeadlineKey, commandRetentionDeadlineMillis, commandId)
            end
            redis.call('RPUSH', readyCommandsKey, referenceValue)
            redis.call('SADD', queuesKey, deliveryQueueKey)
            return {'QUEUED', ''}
            """;
    private static final String CLAIM_READY_REFERENCE_SCRIPT = """
            local readyCommandsKey = KEYS[1]
            local inflightCommandsKey = KEYS[2]
            local visibilityDeadlineMillis = tonumber(ARGV[1])
            local referenceValue = redis.call('LPOP', readyCommandsKey)
            if not referenceValue then
              return nil
            end
            redis.call('ZADD', inflightCommandsKey, visibilityDeadlineMillis, referenceValue)
            return referenceValue
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespacePrefix;
    private final Map<String, LocalConsumerEvidence> localConsumers = new ConcurrentHashMap<>();
    private final int maxQueuedCommandsPerQueue;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final TransportDeliveryCommandBatchCodec codec = new TransportDeliveryCommandBatchCodec();

    public RedisTransportDeliveryCommandHandoff(String redisUri,
                                                String namespacePrefix,
                                                int maxQueuedCommandsPerQueue) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedCommandsPerQueue,
                true
        );
    }

    RedisTransportDeliveryCommandHandoff(RedisClient redisClient,
                                         String namespacePrefix,
                                         int maxQueuedCommandsPerQueue,
                                         boolean ownsClient) {
        this(
                redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                namespacePrefix,
                maxQueuedCommandsPerQueue,
                ownsClient
        );
    }

    RedisTransportDeliveryCommandHandoff(StatefulRedisConnection<String, String> connection,
                                         String namespacePrefix,
                                         int maxQueuedCommandsPerQueue) {
        this(null, connection, namespacePrefix, maxQueuedCommandsPerQueue, false);
    }

    private RedisTransportDeliveryCommandHandoff(RedisClient redisClient,
                                                 StatefulRedisConnection<String, String> connection,
                                                 String namespacePrefix,
                                                 int maxQueuedCommandsPerQueue,
                                                 boolean ownsClient) {
        if (maxQueuedCommandsPerQueue <= 0) {
            throw new IllegalArgumentException("maxQueuedCommandsPerQueue must be positive");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.namespacePrefix = normalizeRequired(namespacePrefix, "namespacePrefix");
        this.maxQueuedCommandsPerQueue = maxQueuedCommandsPerQueue;
        this.ownsClient = ownsClient;
    }

    @Override
    public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
        Objects.requireNonNull(offer, "offer");
        String deliveryQueueKey = normalizeRequired(offer.deliveryQueueKey(), "deliveryQueueKey");
        List<DeliveryCommand> commandsToOffer = offer.commands();
        if (!running.get()) {
            return commandsToOffer.stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "delivery command handoff is stopped"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(commandsToOffer.size());
        long now = System.currentTimeMillis();
        long commandRetentionDeadline = now + DEFAULT_COMMAND_RETENTION_MILLIS;
        for (DeliveryCommand command : commandsToOffer) {
            DeliveryCommandReference reference = new DeliveryCommandReference(
                    deliveryQueueKey,
                    command.getCommandId()
            );
            Object raw = commands.eval(
                    OFFER_COMMAND_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            commandStoreKey(deliveryQueueKey),
                            commandRetentionDeadlineKey(deliveryQueueKey),
                            readyCommandsKey(deliveryQueueKey),
                            queuesKey()
                    },
                    Integer.toString(maxQueuedCommandsPerQueue),
                    command.getCommandId(),
                    codec.encode(new DeliveryCommandBatch(deliveryQueueKey, List.of(command))),
                    Long.toString(commandRetentionDeadline),
                    deliveryQueueKey,
                    encodeReference(reference)
            );
            List<?> values = raw instanceof List<?> list ? list : List.of();
            String status = values.isEmpty() ? "BACKPRESSURE" : String.valueOf(values.getFirst());
            String reason = values.size() > 1 ? String.valueOf(values.get(1)) : "delivery command offer failed";
            outcomes.add(DispatchOutcome.fromCommand(
                    command,
                    "QUEUED".equals(status) ? DispatchOutcomeStatus.QUEUED : DispatchOutcomeStatus.BACKPRESSURE,
                    !"QUEUED".equals(status),
                    "QUEUED".equals(status) ? null : reason
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException {
        if (!running.get()) {
            return null;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        do {
            for (String deliveryQueueKey : pollQueuesSnapshot()) {
                reclaimExpiredInflight(deliveryQueueKey);
                String encodedReference = claimReadyReference(deliveryQueueKey);
                if (encodedReference != null && !encodedReference.isBlank()) {
                    DeliveryCommandBatch claimed = materializeClaimedReference(deliveryQueueKey, encodedReference);
                    if (claimed != null) {
                        return claimed;
                    }
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

    private DeliveryCommandBatch materializeClaimedReference(String claimedDeliveryQueueKey, String encodedReference) {
        DeliveryCommandReference reference = decodeReference(encodedReference);
        if (reference == null) {
            removeInflightClaim(claimedDeliveryQueueKey, encodedReference);
            return null;
        }
        String json = commands.hget(commandStoreKey(reference.deliveryQueueKey()), reference.commandId());
        if (json == null || json.isBlank()) {
            removeInflightClaim(claimedDeliveryQueueKey, encodedReference);
            return null;
        }
        DeliveryCommandBatch stored = codec.decode(json);
        DeliveryCommand command = stored.items().getFirst();
        String selectedWorkerId = command.getSelectedWorkerId();
        LocalConsumerEvidence local = localConsumers.get(selectedWorkerConsumerKey(reference.deliveryQueueKey(), selectedWorkerId));
        if (local != null && local.leaseExpireAtEpochMillis() <= System.currentTimeMillis()) {
            localConsumers.remove(selectedWorkerConsumerKey(local.deliveryQueueKey(), local.selectedWorkerId()), local);
            local = null;
        }
        ConsumerEvidence currentConsumer = currentConsumerEvidence(
                reference.deliveryQueueKey(),
                selectedWorkerId,
                System.currentTimeMillis()
        );
        if (currentConsumer == null) {
            if (local != null) {
                localConsumers.remove(selectedWorkerConsumerKey(local.deliveryQueueKey(), local.selectedWorkerId()), local);
                removeInflightClaim(claimedDeliveryQueueKey, encodedReference);
                commands.rpush(readyCommandsKey(reference.deliveryQueueKey()), encodedReference);
                return null;
            }
        } else if (local == null || !local.endpointLeaseId().equals(currentConsumer.endpointLeaseId())) {
            if (local != null) {
                localConsumers.remove(selectedWorkerConsumerKey(local.deliveryQueueKey(), local.selectedWorkerId()), local);
            }
            removeInflightClaim(claimedDeliveryQueueKey, encodedReference);
            commands.rpush(readyCommandsKey(reference.deliveryQueueKey()), encodedReference);
            return null;
        }
        return new DeliveryCommandBatch(
                reference.deliveryQueueKey(),
                List.of(reference),
                List.of(command)
        );
    }

    @Override
    public void complete(DeliveryCommandBatch batch, List<DispatchOutcome> outcomes) {
        if (batch == null || batch.references().isEmpty()) {
            return;
        }
        for (DeliveryCommandReference reference : batch.references()) {
            String encodedReference = encodeReference(reference);
            commands.zrem(inflightCommandsKey(reference.deliveryQueueKey()), encodedReference);
            commands.hdel(commandStoreKey(reference.deliveryQueueKey()), reference.commandId());
            commands.zrem(commandRetentionDeadlineKey(reference.deliveryQueueKey()), reference.commandId());
        }
    }

    @Override
    public void claimConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        String selectedWorkerId = normalizeRequired(claim.selectedWorkerId(), "selectedWorkerId");
        LocalConsumerEvidence local = new LocalConsumerEvidence(
                deliveryQueueKey,
                selectedWorkerId,
                claim.endpointLeaseId(),
                claim.leaseExpireAtEpochMillis()
        );
        localConsumers.put(selectedWorkerConsumerKey(deliveryQueueKey, selectedWorkerId), local);
        commands.hset(selectedWorkerConsumersKey(deliveryQueueKey), selectedWorkerId, encodeConsumerEvidence(claim));
        commands.zadd(selectedWorkerConsumerDeadlinesKey(deliveryQueueKey), claim.leaseExpireAtEpochMillis(), selectedWorkerId);
        commands.sadd(queuesKey(), deliveryQueueKey);
    }

    @Override
    public void releaseConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        String selectedWorkerId = normalizeRequired(claim.selectedWorkerId(), "selectedWorkerId");
        String localKey = selectedWorkerConsumerKey(deliveryQueueKey, selectedWorkerId);
        localConsumers.computeIfPresent(localKey,
                (ignored, current) -> claim.endpointLeaseId().equals(current.endpointLeaseId()) ? null : current);
        ConsumerEvidence current = currentConsumerEvidence(deliveryQueueKey, selectedWorkerId, System.currentTimeMillis());
        if (current == null
                || !claim.endpointLeaseId().equals(current.endpointLeaseId())) {
            return;
        }
        commands.hdel(selectedWorkerConsumersKey(deliveryQueueKey), selectedWorkerId);
        commands.zrem(selectedWorkerConsumerDeadlinesKey(deliveryQueueKey), selectedWorkerId);
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

    int queuedBatches(String deliveryQueueKey) {
        return Math.toIntExact(commands.hlen(commandStoreKey(deliveryQueueKey)));
    }

    long readyReferencesForTest(String deliveryQueueKey) {
        return commands.llen(readyCommandsKey(deliveryQueueKey));
    }

    long inflightReferencesForTest(String deliveryQueueKey) {
        return commands.zcard(inflightCommandsKey(deliveryQueueKey));
    }

    void expireInflightForTest(String deliveryQueueKey) {
        String inflightKey = inflightCommandsKey(deliveryQueueKey);
        long expiredAt = System.currentTimeMillis() - 1L;
        for (String encodedReference : commands.zrange(inflightKey, 0, -1)) {
            commands.zadd(inflightKey, expiredAt, encodedReference);
        }
    }

    void deleteCommandPayloadForTest(String deliveryQueueKey, String commandId) {
        commands.hdel(commandStoreKey(deliveryQueueKey), commandId);
        commands.zrem(commandRetentionDeadlineKey(deliveryQueueKey), commandId);
    }

    void pushReadyReferenceForTest(String deliveryQueueKey, String encodedReference) {
        commands.rpush(readyCommandsKey(deliveryQueueKey), encodedReference);
    }

    String encodeReferenceForTest(DeliveryCommandReference reference) {
        return encodeReference(reference);
    }

    void clearForTest(String deliveryQueueKey) {
        String normalizedQueueKey = normalizeRequired(deliveryQueueKey, "deliveryQueueKey");
        commands.del(readyCommandsKey(normalizedQueueKey));
        commands.del(inflightCommandsKey(normalizedQueueKey));
        commands.del(commandStoreKey(normalizedQueueKey));
        commands.del(commandRetentionDeadlineKey(normalizedQueueKey));
        commands.del(selectedWorkerConsumersKey(normalizedQueueKey));
        commands.del(selectedWorkerConsumerDeadlinesKey(normalizedQueueKey));
        commands.srem(queuesKey(), normalizedQueueKey);
    }

    void claimConsumerForTest(String deliveryBucketId, String selectedWorkerId, String endpointLeaseId) {
        claimConsumer(new DeliveryCommandConsumerClaim(
                deliveryBucketId,
                selectedWorkerId,
                endpointLeaseId,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5L)
        ));
    }

    private String claimReadyReference(String deliveryQueueKey) {
        Object raw = commands.eval(
                CLAIM_READY_REFERENCE_SCRIPT,
                ScriptOutputType.VALUE,
                new String[]{
                        readyCommandsKey(deliveryQueueKey),
                        inflightCommandsKey(deliveryQueueKey)
                },
                Long.toString(System.currentTimeMillis() + DEFAULT_VISIBILITY_TIMEOUT_MILLIS)
        );
        return raw instanceof String value ? value : null;
    }

    private void removeInflightClaim(String deliveryQueueKey, String encodedReference) {
        commands.zrem(inflightCommandsKey(deliveryQueueKey), encodedReference);
    }

    private void reclaimExpiredInflight(String deliveryQueueKey) {
        long now = System.currentTimeMillis();
        List<String> expired = commands.zrangebyscore(
                inflightCommandsKey(deliveryQueueKey),
                Range.create(Double.NEGATIVE_INFINITY, (double) now)
        );
        for (String encodedReference : expired) {
            if (commands.zrem(inflightCommandsKey(deliveryQueueKey), encodedReference) > 0L) {
                DeliveryCommandReference reference = decodeReference(encodedReference);
                if (reference != null && commands.hexists(commandStoreKey(reference.deliveryQueueKey()), reference.commandId())) {
                    commands.rpush(readyCommandsKey(reference.deliveryQueueKey()), encodedReference);
                }
            }
        }
    }

    private ConsumerEvidence currentConsumerEvidence(String deliveryQueueKey, String selectedWorkerId, long nowEpochMillis) {
        String normalizedWorkerId = normalizeNullable(selectedWorkerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        ConsumerEvidence evidence = decodeConsumerEvidence(
                commands.hget(selectedWorkerConsumersKey(deliveryQueueKey), normalizedWorkerId)
        );
        if (evidence == null) {
            commands.zrem(selectedWorkerConsumerDeadlinesKey(deliveryQueueKey), normalizedWorkerId);
            return null;
        }
        if (evidence.leaseExpireAtEpochMillis() <= nowEpochMillis) {
            commands.hdel(selectedWorkerConsumersKey(deliveryQueueKey), normalizedWorkerId);
            commands.zrem(selectedWorkerConsumerDeadlinesKey(deliveryQueueKey), normalizedWorkerId);
            return null;
        }
        return evidence;
    }

    private String commandStoreKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":q:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"))
                + ":commands";
    }

    private String commandRetentionDeadlineKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":q:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"))
                + ":command-retention-deadlines";
    }

    private String readyCommandsKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":q:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"))
                + ":ready-commands";
    }

    private String inflightCommandsKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":q:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"))
                + ":inflight-commands";
    }

    private String selectedWorkerConsumersKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":selected-worker-consumers:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"));
    }

    private String selectedWorkerConsumerDeadlinesKey(String deliveryQueueKey) {
        return namespacePrefix
                + ":selected-worker-consumer-deadlines:" + encodeToken(normalizeRequired(deliveryQueueKey, "deliveryQueueKey"));
    }

    private String queuesKey() {
        return namespacePrefix + ":queues";
    }

    private static String selectedWorkerConsumerKey(String deliveryQueueKey, String selectedWorkerId) {
        return normalizeRequired(deliveryQueueKey, "deliveryQueueKey")
                + "\n"
                + normalizeRequired(selectedWorkerId, "selectedWorkerId");
    }

    private List<String> pollQueuesSnapshot() {
        List<String> queues = new ArrayList<>();
        for (LocalConsumerEvidence local : List.copyOf(localConsumers.values())) {
            if (local.leaseExpireAtEpochMillis() <= System.currentTimeMillis()) {
                localConsumers.remove(selectedWorkerConsumerKey(local.deliveryQueueKey(), local.selectedWorkerId()), local);
                continue;
            }
            if (!queues.contains(local.deliveryQueueKey())) {
                queues.add(local.deliveryQueueKey());
            }
        }
        for (String deliveryQueueKey : commands.smembers(queuesKey())) {
            String normalized = normalizeNullable(deliveryQueueKey);
            if (normalized != null && !queues.contains(normalized)) {
                queues.add(normalized);
            }
        }
        return queues;
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value) {
        return new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String encodeReference(DeliveryCommandReference reference) {
        return encodeToken(reference.deliveryQueueKey())
                + MEMBER_SEPARATOR
                + encodeToken(reference.commandId());
    }

    private static DeliveryCommandReference decodeReference(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(MEMBER_SEPARATOR, -1);
        if (parts.length != 2) {
            return null;
        }
        return new DeliveryCommandReference(
                decodeToken(parts[0]),
                decodeToken(parts[1])
        );
    }

    private static String encodeConsumerEvidence(DeliveryCommandConsumerClaim claim) {
        return String.join("|",
                CONSUMER_EVIDENCE_VERSION,
                encodeToken(claim.selectedWorkerId()),
                encodeToken(claim.endpointLeaseId()),
                Long.toString(claim.leaseExpireAtEpochMillis())
        );
    }

    private static ConsumerEvidence decodeConsumerEvidence(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4 || !CONSUMER_EVIDENCE_VERSION.equals(parts[0])) {
            return null;
        }
        return new ConsumerEvidence(
                decodeToken(parts[1]),
                decodeToken(parts[2]),
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

    private record ConsumerEvidence(String selectedWorkerId,
                                    String endpointLeaseId,
                                    long leaseExpireAtEpochMillis) {
    }

    private record LocalConsumerEvidence(String deliveryQueueKey,
                                         String selectedWorkerId,
                                         String endpointLeaseId,
                                         long leaseExpireAtEpochMillis) {
    }
}
