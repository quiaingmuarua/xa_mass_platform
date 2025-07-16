package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 通用Stream事件总线门面，支持内存、Redis等多种消息流实现。
 * 通过注入MessageStream实现解耦，便于测试和扩展。
 */
public class StreamEventBusFacade implements EventBusFacade {
    private static final Logger log = LoggerFactory.getLogger(StreamEventBusFacade.class);
    private final MessageStream<MassEvent> stream;
    private final MassEventDispatcher dispatcher = new MassEventDispatcher();
    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "stream-event-consumer"));
    private final ExecutorService handlerExecutor = Executors.newFixedThreadPool(4, r -> new Thread(r, "stream-event-handler"));
    private volatile boolean running = true;

    /**
     * 构造方法，注入消息流实现
     * @param stream 消息流（可为内存、Redis等）
     */
    public StreamEventBusFacade(MessageStream<MassEvent> stream) {
        this.stream = stream;
        startListenerLoop();
    }

    /**
     * 启动消费线程，循环拉取消息并分发
     */
    private void startListenerLoop() {
        consumerExecutor.submit(() -> {
            while (running) {
                try {
                    MessageStream.StreamMessage<MassEvent> msg = stream.poll(5000, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        handlerExecutor.submit(() -> {
                            dispatcher.dispatch(msg.getMessage());
                            // 消费成功后ack
                            stream.ack(msg.getMessageId());
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Stream listener error, will retry: {}", e.getMessage(), e);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        });
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
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        stream.offer(event);
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注册带有@MassSubscribe注解的listener实例");
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("请直接注销带有@MassSubscribe注解的listener实例");
    }

    @Override
    public void shutdown() {
        running = false;
        consumerExecutor.shutdown();
        handlerExecutor.shutdown();
        try {
            if (!consumerExecutor.awaitTermination(5, TimeUnit.SECONDS)) consumerExecutor.shutdownNow();
            if (!handlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) handlerExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
     * 获取流配置信息
     * @return 配置信息字符串
     */
    public String getStreamInfo() {
        return String.format("Stream: %s, Handlers: %d", stream.getName(), getListenerCount());
    }
} 