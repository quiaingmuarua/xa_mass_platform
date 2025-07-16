package com.xa.mass.base.eventbus.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.tool.RedisConnectionManager;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisStreamEventBusFacade implements EventBusFacade {
    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (src, typeOfSrc, context) ->
            new com.google.gson.JsonPrimitive(src.toString()))
        .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, typeOfT, context) ->
            Instant.parse(json.getAsString()))
        .create();
    private final MassEventDispatcher dispatcher = new MassEventDispatcher();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    public RedisStreamEventBusFacade(String streamKey, String group, String consumerName) {
        this.streamKey = streamKey;
        this.group = group;
        this.consumerName = consumerName;
        initGroup();
        startListenerLoop();
    }

    private void initGroup() {
        StatefulRedisConnection<String, String> conn = RedisConnectionManager.getConnection();
        RedisCommands<String, String> commands = conn.sync();
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), group);
        } catch (Exception ignored) {
            // group已存在
        }
    }

    @Override
    public void register(Object listener) {
        dispatcher.registerListener(listener);
    }

    @Override
    public void unregister(Object listener) {
        dispatcher.unregisterListener(listener);
    }

    @Override
    public <E extends MassEvent> void post(E event) {
        StatefulRedisConnection<String, String> conn = RedisConnectionManager.getConnection();
        RedisCommands<String, String> commands = conn.sync();
        String json = gson.toJson(event);
        commands.xadd(streamKey, Map.of("event", json, "type", event.getClass().getName()));
    }

    private void startListenerLoop() {
        executor.submit(() -> {
            StatefulRedisConnection<String, String> conn = RedisConnectionManager.getConnection();
            RedisCommands<String, String> commands = conn.sync();
            while (running) {
                try {
                    // 使用阻塞读(BLOCK)和数量限制(COUNT)，比Thread.sleep()轮询更高效
                    List<StreamMessage<String, String>> messages =
                            commands.xreadgroup(
                                    Consumer.from(group, consumerName),
                                    XReadArgs.Builder.block(5000).count(10), // 阻塞等待5秒，最多取10条
                                    XReadArgs.StreamOffset.lastConsumed(streamKey));
                    for (StreamMessage<String, String> msg : messages) {
                        String json = msg.getBody().get("event");
                        String type = msg.getBody().get("type");
                        try {
                            Class<?> clazz = Class.forName(type);
                            Object event = gson.fromJson(json, clazz);
                            dispatcher.dispatch(event);
                        } catch (Exception e) {
                            System.err.println("Error processing Redis Stream event: " + type);
                            e.printStackTrace();
                        }
                        // 确认(ack)消息，防止重复消费
                        commands.xack(streamKey, group, msg.getId());
                    }
                } catch (io.lettuce.core.RedisException e) {
                    System.err.println("Redis listener error, will retry in 5s: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    @Override
    public void shutdown() {
        running = false;
        executor.shutdown();
    }

    /**
     * 获取已注册的监听器数量
     * @return 监听器数量
     */
    public int getListenerCount() {
        return dispatcher.getTotalHandlerCount();
    }

    /**
     * 获取指定事件类型的处理器数量
     * @param eventType 事件类型
     * @return 处理器数量
     */
    public int getHandlerCount(Class<?> eventType) {
        return dispatcher.getHandlerCount(eventType);
    }

    /**
     * 获取Redis Stream配置信息
     * @return 配置信息字符串
     */
    public String getStreamInfo() {
        return String.format("StreamKey: %s, Group: %s, Consumer: %s, Handlers: %d", 
            streamKey, group, consumerName, getListenerCount());
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注册带有@MassSubscribe注解的listener实例");
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注销带有@MassSubscribe注解的listener实例");
    }
} 