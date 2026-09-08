package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("xa.mass.ReportQueue")
@Category("XA Mass")
@Enabled(false)
@StackTrace(false)
final class ReportQueueEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(ReportQueueEvent.class);
    public String destination;
    public String action;
    public int depth;
    public int count;

    static boolean recordingEnabled() {
        try { return TYPE.isEnabled(); }
        catch (RuntimeException ignored) { return false; }
    }

    static void record(DeliveryEndpoint destination, String action, int depth, int count) {
        try {
            if (!recordingEnabled()) return;
            var event = new ReportQueueEvent();
            event.destination = destination.name();
            event.action = action;
            event.depth = depth;
            event.count = count;
            event.commit();
            // Keep the Event local out of the exception handler's merged bytecode frame.
            return;
        } catch (RuntimeException ignored) { /* Observation never changes Queue admission. */ }
    }
}
