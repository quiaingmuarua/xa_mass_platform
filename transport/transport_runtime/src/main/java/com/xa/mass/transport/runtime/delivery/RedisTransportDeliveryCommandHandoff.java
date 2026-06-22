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
        AdapterMailboxConsumerRegistry,
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
            local adapterMailboxKey = ARGV[5]
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
            redis.call('SADD', queuesKey, adapterMailboxKey)
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
    private final Map<String, LocalMailboxConsumerEvidence> localConsumers = new ConcurrentHashMap<>();
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
    public List<DispatchOutcome> offer(AdapterMailboxDeliveryOffer offer) {
        Objects.requireNonNull(offer, "offer");
        String adapterMailboxKey = normalizeRequired(offer.adapterMailboxKey(), "adapterMailboxKey");
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
        if (currentMailboxConsumerEvidence(adapterMailboxKey, System.currentTimeMillis()) == null) {
            return commandsToOffer.stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "adapter mailbox has no active consumer"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(commandsToOffer.size());
        long now = System.currentTimeMillis();
        long commandRetentionDeadline = now + DEFAULT_COMMAND_RETENTION_MILLIS;
        for (DeliveryCommand command : commandsToOffer) {
            DeliveryCommandReference reference = new DeliveryCommandReference(
                    adapterMailboxKey,
                    command.getCommandId()
            );
            Object raw = commands.eval(
                    OFFER_COMMAND_SCRIPT,
                    ScriptOutputType.MULTI,
                    new String[]{
                            commandStoreKey(adapterMailboxKey),
                            commandRetentionDeadlineKey(adapterMailboxKey),
                            readyCommandsKey(adapterMailboxKey),
                            queuesKey()
                    },
                    Integer.toString(maxQueuedCommandsPerQueue),
                    command.getCommandId(),
                    codec.encode(new DeliveryCommandBatch(adapterMailboxKey, List.of(command))),
                    Long.toString(commandRetentionDeadline),
                    adapterMailboxKey,
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
    public DeliveryCommandBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException {
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
                DeliveryCommandBatch claimed = materializeClaimedReference(mailboxKey, encodedReference);
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

    private DeliveryCommandBatch materializeClaimedReference(String claimedAdapterMailboxKey, String encodedReference) {
        DeliveryCommandReference reference = decodeReference(encodedReference);
        if (reference == null) {
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            return null;
        }
        String json = commands.hget(commandStoreKey(reference.adapterMailboxKey()), reference.commandId());
        if (json == null || json.isBlank()) {
            removeInflightClaim(claimedAdapterMailboxKey, encodedReference);
            return null;
        }
        DeliveryCommandBatch stored = codec.decode(json);
        DeliveryCommand command = stored.items().getFirst();
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
            commands.rpush(readyCommandsKey(reference.adapterMailboxKey()), encodedReference);
            return null;
        }
        return new DeliveryCommandBatch(
                reference.adapterMailboxKey(),
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
            commands.zrem(inflightCommandsKey(reference.adapterMailboxKey()), encodedReference);
            commands.hdel(commandStoreKey(reference.adapterMailboxKey()), reference.commandId());
            commands.zrem(commandRetentionDeadlineKey(reference.adapterMailboxKey()), reference.commandId());
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
        return Math.toIntExact(commands.hlen(commandStoreKey(adapterMailboxKey)));
    }

    long readyReferencesForTest(String adapterMailboxKey) {
        return commands.llen(readyCommandsKey(adapterMailboxKey));
    }

    long inflightReferencesForTest(String adapterMailboxKey) {
        return commands.zcard(inflightCommandsKey(adapterMailboxKey));
    }

    void expireInflightForTest(String adapterMailboxKey) {
        String inflightKey = inflightCommandsKey(adapterMailboxKey);
        long expiredAt = System.currentTimeMillis() - 1L;
        for (String encodedReference : commands.zrange(inflightKey, 0, -1)) {
            commands.zadd(inflightKey, expiredAt, encodedReference);
        }
    }

    void deleteCommandPayloadForTest(String adapterMailboxKey, String commandId) {
        commands.hdel(commandStoreKey(adapterMailboxKey), commandId);
        commands.zrem(commandRetentionDeadlineKey(adapterMailboxKey), commandId);
    }

    void pushReadyReferenceForTest(String adapterMailboxKey, String encodedReference) {
        commands.rpush(readyCommandsKey(adapterMailboxKey), encodedReference);
    }

    String encodeReferenceForTest(DeliveryCommandReference reference) {
        return encodeReference(reference);
    }

    void clearForTest(String adapterMailboxKey) {
        String normalizedMailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        commands.del(readyCommandsKey(normalizedMailboxKey));
        commands.del(inflightCommandsKey(normalizedMailboxKey));
        commands.del(commandStoreKey(normalizedMailboxKey));
        commands.del(commandRetentionDeadlineKey(normalizedMailboxKey));
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
                        readyCommandsKey(adapterMailboxKey),
                        inflightCommandsKey(adapterMailboxKey)
                },
                Long.toString(System.currentTimeMillis() + DEFAULT_VISIBILITY_TIMEOUT_MILLIS)
        );
        return raw instanceof String value ? value : null;
    }

    private void removeInflightClaim(String adapterMailboxKey, String encodedReference) {
        commands.zrem(inflightCommandsKey(adapterMailboxKey), encodedReference);
    }

    private void reclaimExpiredInflight(String adapterMailboxKey) {
        long now = System.currentTimeMillis();
        List<String> expired = commands.zrangebyscore(
                inflightCommandsKey(adapterMailboxKey),
                Range.create(Double.NEGATIVE_INFINITY, (double) now)
        );
        for (String encodedReference : expired) {
            if (commands.zrem(inflightCommandsKey(adapterMailboxKey), encodedReference) > 0L) {
                DeliveryCommandReference reference = decodeReference(encodedReference);
                if (reference != null && commands.hexists(commandStoreKey(reference.adapterMailboxKey()), reference.commandId())) {
                    commands.rpush(readyCommandsKey(reference.adapterMailboxKey()), encodedReference);
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

    private String commandStoreKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":commands";
    }

    private String commandRetentionDeadlineKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":command-retention-deadlines";
    }

    private String readyCommandsKey(String adapterMailboxKey) {
        return namespacePrefix
                + ":mailbox:" + encodeToken(normalizeRequired(adapterMailboxKey, "adapterMailboxKey"))
                + ":ready-commands";
    }

    private String inflightCommandsKey(String adapterMailboxKey) {
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

    private static String encodeReference(DeliveryCommandReference reference) {
        return encodeToken(reference.adapterMailboxKey())
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
