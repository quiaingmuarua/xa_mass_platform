package com.xa.mass.base.channel.eventbus.core;

public final class EventBusFactory {
    private static volatile EventBusFacade<MassEvent> instance;

    private EventBusFactory() {
    }

    public static EventBusFacade<MassEvent> get(String type) {
        if (instance == null) {
            synchronized (EventBusFactory.class) {
                if (instance == null) {
                    if ("guava".equalsIgnoreCase(type)) {
                        instance = new GuavaEventBusFacade(4);
                    } else if ("redis".equalsIgnoreCase(type)) {
                        throw new UnsupportedOperationException("Redis EventBus facade is not part of the converged runtime path");
                    } else {
                        throw new IllegalArgumentException("Unknown EventBus type: " + type);
                    }
                }
            }
        }
        return instance;
    }
}
