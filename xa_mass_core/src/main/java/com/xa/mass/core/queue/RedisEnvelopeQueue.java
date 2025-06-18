package com.xa.mass.core.queue;

import com.google.gson.Gson;
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
 * 基于 Envelope 的 Redis Stream 消息队列实现，封装发布和消费逻辑
 */
public class RedisEnvelopeQueue implements MessageQueue<Envelope> {

    private static final Logger logger = LoggerFactory.getLogger(RedisEnvelopeQueue.class);

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisStreamCommands<String, String> commands;
    private final Gson gson;

    public RedisEnvelopeQueue(String streamKey,
                              String groupName,
                              String consumerName,
                              StatefulRedisConnection<String, String> connection,
                              Gson gson) {
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.connection = connection;
        this.commands = connection.sync();
        this.gson = gson;
        initGroup();
    }

    private void initGroup() {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0-0"), groupName, XGroupCreateArgs.Builder.mkstream(true));
        } catch (RedisBusyException e) {
            logger.info("Group already exists: {}", groupName);
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
            if (envelope.getTraceId() != null) fields.put("traceId", envelope.getTraceId());
            commands.xadd(streamKey, fields);
        } catch (Exception e) {
            logger.error("Offer failed", e);
        }
    }

    @Override
    public Envelope poll(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            List<StreamMessage<String, String>> messages = commands.xreadgroup(
                    Consumer.from(groupName, consumerName),
                    XReadArgs.Builder.count(1).block(unit.toMillis(timeout)),
                    XReadArgs.StreamOffset.lastConsumed(streamKey));

            if (messages != null && !messages.isEmpty()) {
                StreamMessage<String, String> msg = messages.get(0);
                Envelope envelope = fromFields(msg.getBody());
                commands.xack(streamKey, groupName, msg.getId());
                return envelope;
            }
        } catch (RedisCommandTimeoutException e) {
            // no-op
        }
        return null;
    }

    private Envelope fromFields(Map<String, String> map) {
        return Envelope.builder().rawJson(map.get("rawJson")).deviceId(map.get("deviceId"))
                .traceId(map.get("traceId")).
                receivedAt(Long.parseLong(map.getOrDefault("receivedAt", String.valueOf(System.currentTimeMillis()))))
                .connRole(map.get("connRole")).build();
    }

    @Override
    public boolean isEmpty() {
        return commands.xlen(streamKey) == 0;
    }

    @Override
    public int size() {
        return commands.xlen(streamKey).intValue();
    }

    @Override
    public String getName() {
        return "RedisEnvelopeQueue::" + streamKey;
    }
}
