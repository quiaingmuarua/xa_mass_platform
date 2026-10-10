package com.xa.mass.kernel.pacer.dispatch;

import jdk.jfr.*;

/** Default-off aggregate evidence. No identities, scores or business content. */
@Name("xa.mass.CandidateRecycle") @Label("Full candidate recycle hints") @Category("XA Mass")
@Enabled(false) @StackTrace(false)
final class CandidateRecycleEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(CandidateRecycleEvent.class);
    public int pending, peak;
    public long accepted, dropped, attempted, recycled, stale, failed, retired;
    static void emit(int pending, int peak, long accepted, long dropped, long attempted,
            long recycled, long stale, long failed, long retired) {
        if (!TYPE.isEnabled()) return;
        var event = new CandidateRecycleEvent();
        event.pending = pending; event.peak = peak; event.accepted = accepted; event.dropped = dropped;
        event.attempted = attempted; event.recycled = recycled; event.stale = stale;
        event.failed = failed; event.retired = retired; event.commit();
    }
}
