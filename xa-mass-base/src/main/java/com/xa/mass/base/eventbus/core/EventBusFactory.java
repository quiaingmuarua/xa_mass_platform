package com.xa.mass.base.eventbus.core;

public class EventBusFactory {
    private static volatile EventBusFacade INSTANCE;

    public static EventBusFacade get(String type) {
        if (INSTANCE == null) {
            synchronized (EventBusFactory.class) {
                if (INSTANCE == null) {
                    if ("guava".equalsIgnoreCase(type)) {
                        INSTANCE = new GuavaEventBusFacade(4);
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
