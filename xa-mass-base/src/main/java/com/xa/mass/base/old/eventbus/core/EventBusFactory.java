package com.xa.mass.base.old.eventbus.core;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;

@Deprecated
public class EventBusFactory {
    private static volatile EventBusFacade INSTANCE;

    public static EventBusFacade get(String type) {
        if (INSTANCE == null) {
            synchronized (EventBusFactory.class) {
                if (INSTANCE == null) {
                    if ("guava".equalsIgnoreCase(type)) {
                        INSTANCE = new GuavaEventBusFacade(4);
                    }
                    else if ("redis".equalsIgnoreCase(type)) {
                        INSTANCE = new RedisStreamEventBusFacade("mass_event_stream", "mass_group", "consumer-1");
                    }
                    // else if ("spring".equalsIgnoreCase(type)) { ... }
                    else {
                        throw new IllegalArgumentException("Unknown EventBus type: " + type);
                    }
                }
            }
        }
        return INSTANCE;
    }
    // 可扩展：支持重置、热切换、桥接等
}
