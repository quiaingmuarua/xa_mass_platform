package com.xa.mass.workermatching.pool;

import java.util.IdentityHashMap;
import java.util.Map;

/** Process capacity accounting only: no Worker identity, projection or matching operation. */
public final class CandidateBudget {
    static final int PER_ELIGIBILITY = 1000, PROCESS = 10_000, ELIGIBILITIES = 100;
    private final Map<Object, Integer> counts = new IdentityHashMap<>();
    private int size;
    private long admitted, consumed, expired, capacityLimited;
    public synchronized int available() { return PROCESS - size; }
    synchronized int room(Object pool) {
        int count = counts.getOrDefault(pool, 0);
        return count == 0 && counts.size() == ELIGIBILITIES ? 0 : Math.min(PROCESS - size, PER_ELIGIBILITY - count);
    }
    synchronized boolean acquire(Object pool) {
        int count = counts.getOrDefault(pool, 0);
        if (size == PROCESS || count == PER_ELIGIBILITY || count == 0 && counts.size() == ELIGIBILITIES) {
            capacityLimited++; return false;
        }
        counts.put(pool, count + 1); size++; admitted++; return true;
    }
    synchronized void release(Object pool, int count, boolean expiration) {
        if (count == 0) return;
        int remaining = counts.get(pool) - count;
        if (remaining == 0) counts.remove(pool); else counts.put(pool, remaining);
        size -= count;
        if (expiration) expired += count; else consumed += count;
    }
    public synchronized String diagnostics() {
        return "resident=" + size + " eligibilities=" + counts.size() + " admitted=" + admitted
                + " consumed=" + consumed + " expiredUnused=" + expired + " capacityLimited=" + capacityLimited;
    }
}
