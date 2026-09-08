package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Fixed remote-operation timings; no route, identity, request or response content. */
@Name("xa.mass.AdapterRemote")
@Category("XA Mass")
@Enabled(false)
@StackTrace(false)
final class AdapterRemoteEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(AdapterRemoteEvent.class);
    public String operation;
    public int batchSize;
    public int httpStatus;
    public boolean failed = true;

    static AdapterRemoteEvent start(String operation, int batchSize) {
        try {
            if (!TYPE.isEnabled()) return null;
            var event = new AdapterRemoteEvent();
            event.operation = operation;
            event.batchSize = batchSize;
            event.begin();
            return event;
        } catch (RuntimeException ignored) { return null; }
    }

    static void finish(AdapterRemoteEvent event) {
        if (event == null) return;
        try { event.end(); event.commit(); }
        catch (RuntimeException ignored) { /* Never change delivery failure classification. */ }
    }
}
