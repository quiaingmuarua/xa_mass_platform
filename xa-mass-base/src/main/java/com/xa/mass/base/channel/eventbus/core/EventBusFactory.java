package com.xa.mass.base.channel.eventbus.core;

public final class EventBusFactory {
    private static volatile EventBusFacade<MassEvent> instance;

    private EventBusFactory() {
    }

    public static EventBusFacade<MassEvent> get(String type) {
        if (!"runtime".equalsIgnoreCase(type)) {
            if ("redis".equalsIgnoreCase(type)) {
                throw new UnsupportedOperationException("Redis EventBus facade is not part of the converged runtime path");
            }
            throw new IllegalArgumentException("Unknown EventBus type: " + type);
        }
        if (instance == null) {
            synchronized (EventBusFactory.class) {
                if (instance == null) {
                    instance = new RuntimeAsyncEventBusFacade();
                }
            }
        }
        return instance;
    }
}
