package com.xa.mass.gateway.queue;


import com.xa.mass.base.channel.queue.api.MessageQueue;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStreamCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 支持读写分离的 Redis Stream Envelope 队列。
 */
public class RedisEnvelopeQueue implements MessageQueue<Envelope> {

    private static final Logger logger = LoggerFactory.getLogger(RedisEnvelopeQueue.class);

    private final String streamKey;
    private final String groupName;
    private final String consumerName;

    private final RedisStreamCommands<String, String> readCommands;
    private final RedisStreamCommands<String, String> writeCommands;

    public RedisEnvelopeQueue(
            String streamKey,
            String groupName,
            String consumerName,
            StatefulRedisConnection<String, String> readConn,
            StatefulRedisConnection<String, String> writeConn
    ) {
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.readCommands = readConn.sync();
        this.writeCommands = writeConn.sync();
        initGroup();
    }

    private void initGroup() {
        try {
            readCommands.xgroupCreate(
                    XReadArgs.StreamOffset.from(streamKey, "0-0"),
                    groupName,
                    XGroupCreateArgs.Builder.mkstream(true)
            );
        } catch (RedisBusyException e) {
            logger.info("Group already exists: {}", groupName);
        } catch (Exception e) {
            logger.error("Group creation failed", e);
        }
    }

    @Override
    public void offer(Envelope envelope) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("rawJson", envelope.getRawJson());
            fields.put("deviceId", envelope.getDeviceId());
            fields.put("connRole", envelope.getConnRole());
            fields.put("receivedAt", String.valueOf(envelope.getReceivedAt()));
            if (envelope.getTraceId() != null) {
                fields.put("traceId", envelope.getTraceId());
            }

            // 加入 maxlen 避免堆积
            writeCommands.xadd(streamKey, XAddArgs.Builder.maxlen(10000), fields);
        } catch (Exception e) {
            logger.error("Redis xadd failed", e);
        }
    }

    @Override
    public Envelope poll(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            List<StreamMessage<String, String>> messages = readCommands.xreadgroup(
                    Consumer.from(groupName, consumerName),
                    XReadArgs.Builder.count(1).block(unit.toMillis(timeout)),
                    XReadArgs.StreamOffset.lastConsumed(streamKey)
            );

            if (messages != null && !messages.isEmpty()) {
                StreamMessage<String, String> msg = messages.get(0);
                Envelope envelope = fromFields(msg.getBody());
                readCommands.xack(streamKey, groupName, msg.getId());

                return envelope;
            }
        } catch (RedisCommandTimeoutException e) {
            // ignore - treated as no message
        } catch (Exception e) {
            logger.error("Redis xreadgroup failed", e);
        }
        logger.info("Redis queue size" + readCommands.xlen(streamKey));
        return null;
    }

    private Envelope fromFields(Map<String, String> map) {
        return Envelope.builder()
                .rawJson(map.get("rawJson"))
                .deviceId(map.get("deviceId"))
                .connRole(map.get("connRole"))
                .traceId(map.get("traceId"))
                .receivedAt(Long.parseLong(map.getOrDefault("receivedAt", String.valueOf(System.currentTimeMillis()))))
                .build();
    }

    @Override
    public boolean isEmpty() {
        try {
            return readCommands.xlen(streamKey) == 0;
        } catch (Exception e) {
            logger.warn("Redis isEmpty check failed", e);
            return false;
        }
    }

    @Override
    public int size() {
        try {
            return readCommands.xlen(streamKey).intValue();
        } catch (Exception e) {
            logger.warn("Redis size check failed", e);
            return -1;
        }
    }

    @Override
    public String getName() {
        return "RedisEnvelopeQueue::" + streamKey;
    }
}
