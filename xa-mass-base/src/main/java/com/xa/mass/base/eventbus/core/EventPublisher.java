package com.xa.mass.base.eventbus.core;

public class EventPublisher {
    private static final EventBusFacade eventBus = EventBusFactory.get("guava");

    private EventPublisher() {
    }

    public static void post(MassEvent event) {
        // 可加日志、埋点、异常处理等
        eventBus.post(event);
    }
} 