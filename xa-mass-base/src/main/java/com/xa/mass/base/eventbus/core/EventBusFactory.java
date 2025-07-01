package com.xa.mass.base.eventbus.core;


public class EventBusFactory {
    public static EventBusFacade create(String type) {
        switch (type.toLowerCase()) {
            case "guava":
                return new GuavaEventBusFacade(4);
            // case "spring": return new SpringEventBusFacade();
            default:
                throw new IllegalArgumentException("Unknown EventBus type: " + type);
        }
    }
}
