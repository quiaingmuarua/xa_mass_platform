package com.xa.mass.trace.sink;

/**
 * Controls how the sink behaves when its in-memory queue is full.
 *
 * <p><b>DROP</b> (default, production): the incoming event is discarded, a drop
 * counter is incremented, and a rate-limited WARN is logged.  The caller thread
 * is never delayed.
 *
 * <p><b>FALLBACK_SYNC</b> (debug only): when the queue is full the caller thread
 * writes the event directly to the current output file while holding a file lock.
 * <em>Do not use on hot paths — this will block the caller during I/O.</em>
 */
public enum OverflowPolicy {

    /** Drop the event silently; increment the dropped counter. */
    DROP,

    /**
     * Write the event synchronously on the caller thread when the queue is full.
     * <b>Debug / low-throughput use only.  Do not use in production hot paths.</b>
     */
    FALLBACK_SYNC
}
