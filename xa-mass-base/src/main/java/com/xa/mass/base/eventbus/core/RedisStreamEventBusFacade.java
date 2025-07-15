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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.xa.mass.base.eventbus.core.MassSubscribe;

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
    private final List<Object> listeners = new CopyOnWriteArrayList<>();
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
        listeners.add(listener);
    }

    @Override
    public void unregister(Object listener) {
        listeners.remove(listener);
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
                List<StreamMessage<String, String>> messages =
                        commands.xreadgroup(Consumer.from(group, consumerName), XReadArgs.StreamOffset.lastConsumed(streamKey));
                for (StreamMessage<String, String> msg : messages) {
                    String json = msg.getBody().get("event");
                    String type = msg.getBody().get("type");
                    try {
                        Class<?> clazz = Class.forName(type);
                        Object event = gson.fromJson(json, clazz);
                        for (Object listener : listeners) {
                            for (var method : listener.getClass().getMethods()) {
                                if (method.isAnnotationPresent(MassSubscribe.class)) {
                                    Class<?>[] params = method.getParameterTypes();
                                    if (params.length == 1 && params[0].isAssignableFrom(clazz)) {
                                        if (!method.canAccess(listener)) {
                                            method.setAccessible(true);
                                        }
                                        method.invoke(listener, event);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    // 手动ack
                    commands.xack(streamKey, group, msg.getId());
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        });
    }

    @Override
    public void shutdown() {
        running = false;
        executor.shutdown();
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注册带有@Subscribe注解的listener实例");
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注销带有@Subscribe注解的listener实例");
    }
} 