package com.xa.mass.base.channel.eventbus.core;

public final class EventPublisher {
    private static final EventBusFacade<MassEvent> eventBus = EventBusFactory.get("guava");

    private EventPublisher() {
    }

    public static void post(MassEvent event) {
        eventBus.post(event);
    }
}
