package com.xa.mass.server.delivery;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Optional, identity-free timings of the existing Server delivery use cases. */
@Name("xa.mass.ServerDeliveryStage")
@Category("XA Mass")
@Enabled(false)
@StackTrace(false)
public final class DeliveryStageEvent extends Event {
    public enum Stage { DIRECT_BINDING, DIRECT_OFFER, COMMAND_CONSUME }
    private static final EventType TYPE = EventType.getEventType(DeliveryStageEvent.class);
    public String stage;
    public int batchSize;
    public int offered;
    public int occupied;
    public boolean failed = true;

    public static DeliveryStageEvent start(Stage stage, int batchSize) {
        try {
            if (!TYPE.isEnabled()) return null;
            var event = new DeliveryStageEvent();
            event.stage = stage.name();
            event.batchSize = batchSize;
            event.begin();
            return event;
        } catch (RuntimeException ignored) { return null; }
    }

    public static void finish(DeliveryStageEvent event) {
        if (event == null) return;
        try { event.end(); event.commit(); }
        catch (RuntimeException ignored) { /* Diagnostics never decide the use-case outcome. */ }
    }
}
