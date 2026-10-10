package com.xa.mass.kernel.pacer.dispatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CandidateRecycleHintsTest {
    final AtomicLong nanos = new AtomicLong();
    final CandidateRecycleHints hints = new CandidateRecycleHints(nanos::get);
    @Test void fixedDelayDoesNotRenewForTheSameFenceAndDifferentFencesReplace() {
        hints.offer("g", Map.of("same", 1L, "changed", 2L), List.of("same", "changed"));
        nanos.set(TimeUnit.SECONDS.toNanos(9));
        hints.offer("g", Map.of("same", 1L, "changed", 3L), List.of("same", "changed"));
        assertTrue(hints.pollDue("g", 50).isEmpty());
        nanos.set(TimeUnit.SECONDS.toNanos(10));
        assertEquals(Map.of("same", 1L), hints.pollDue("g", 50));
        nanos.set(TimeUnit.SECONDS.toNanos(19));
        assertEquals(Map.of("changed", 3L), hints.pollDue("g", 50));
        assertEquals(0, hints.pending());
    }
    @Test void saturatedGroupsAndRunsStayBoundedAndCanDrain() {
        var offered = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 1100; i++) offered.put("w" + i, 1L);
        for (int g = 0; g < 12; g++) hints.offer("g" + g, offered, List.copyOf(offered.keySet()));
        assertEquals(10_000, hints.pending()); assertEquals(10_000, hints.peak());
        assertEquals(3200, hints.dropped());
        nanos.set(TimeUnit.SECONDS.toNanos(10));
        assertEquals(50, hints.pollDue("g0", 50).size());
        assertEquals(9950, hints.pending());
        hints.retainGroups(List.of("g0"));
        assertEquals(950, hints.pending());
        hints.retainGroups(List.of()); assertEquals(0, hints.pending());
    }
    @Test void newRunHasNoOldHintsAndForeignIdsFailBeforeAdmission() {
        hints.offer("g", Map.of("w", 1L), List.of("w"));
        assertThrows(IllegalArgumentException.class, () -> hints.offer("g", Map.of(), List.of("foreign")));
        nanos.set(TimeUnit.SECONDS.toNanos(10));
        assertTrue(new CandidateRecycleHints(nanos::get).pollDue("g", 50).isEmpty());
        assertEquals(Map.of("w", 1L), hints.pollDue("g", 50));
    }
}
