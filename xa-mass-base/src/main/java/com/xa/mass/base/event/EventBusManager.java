package com.xa.mass.base.event;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.SubscriberExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 事件总线管理器
 * 统一管理 AsyncEventBus 实例，提供便捷的事件发布和监听器注册功能
 */
public class EventBusManager {
    private static final Logger log = LoggerFactory.getLogger(EventBusManager.class);
    
    // 使用单例模式管理事件总线
    private static final AsyncEventBus EVENT_BUS;
    private static final ThreadPoolExecutor EXECUTOR;
    
    static {
        // 创建线程池
        EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
        
        // 自定义异常处理器
        SubscriberExceptionHandler exceptionHandler = (exception, context) -> {
            log.error("Event bus exception: event={}, subscriber={}", 
                     context.getEvent(), context.getSubscriber(), exception);
        };
        
        // 初始化事件总线
        EVENT_BUS = new AsyncEventBus(EXECUTOR, exceptionHandler);
        log.info("EventBusManager initialized with thread pool size: {}", EXECUTOR.getCorePoolSize());
    }
    
    /**
     * 获取事件总线实例
     */
    public static AsyncEventBus getBus() {
        return EVENT_BUS;
    }
    
    /**
     * 注册事件监听器
     */
    public static void register(Object listener) {
        EVENT_BUS.register(listener);
        log.debug("Registered event listener: {}", listener.getClass().getSimpleName());
    }
    
    /**
     * 注销事件监听器
     */
    public static void unregister(Object listener) {
        EVENT_BUS.unregister(listener);
        log.debug("Unregistered event listener: {}", listener.getClass().getSimpleName());
    }
    
    /**
     * 发布事件
     */
    public static void post(Object event) {
        EVENT_BUS.post(event);
        log.debug("Posted event: {}", event.getClass().getSimpleName());
    }
    
    /**
     * 发布混沌事件（带详细日志）
     */
    public static void postChaosEvent(ChaosEvent event) {
        EVENT_BUS.post(event);
        log.info("Posted chaos event: {} - {}", event.getEventType(), event.getDescription());
    }
    
    /**
     * 关闭事件总线（通常在应用关闭时调用）
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
        log.info("EventBusManager shutdown completed");
    }
} 