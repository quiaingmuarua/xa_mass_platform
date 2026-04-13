package com.xa.mass.base.old.eventbus.core;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.MassEvent;

@Deprecated
public class EventPublisher {
    private static final EventBusFacade eventBus = EventBusFactory.get("guava");

    private EventPublisher() {
    }

    public static void post(MassEvent event) {
        // 可加日志、埋点、异常处理等
        eventBus.post(event);
    }
} 