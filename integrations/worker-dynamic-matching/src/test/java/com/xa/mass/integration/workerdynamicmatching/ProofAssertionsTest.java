package com.xa.mass.integration.workerdynamicmatching;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProofAssertionsTest {
    @Test void rejectsHybridLostDeltaAndMergeInsteadOfReplacement() {
        var before = Map.of("sequence", "1", "mirror", "1", "delta-1", "1");
        var after = Map.of("sequence", "2", "mirror", "2", "delta-1", "1", "delta-2", "2");
        var history = List.of(before, after, Map.of("identity", "w"));
        assertDoesNotThrow(() -> ProofAssertions.snapshot(after, history));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.snapshot(Map.of("sequence", "2", "mirror", "1"), history));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.snapshot(Map.of("sequence", "2", "mirror", "2", "delta-2", "2"), history));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.snapshot(Map.of("identity", "w", "delta-1", "1"), history));
    }
    @Test void rejectsWrongActualExecutorPrematureExecutionAndIdentityDrift() {
        assertDoesNotThrow(() -> ProofAssertions.executor("g/actual", Set.of("g/actual"), true));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.executor("g/requested", Set.of("g/actual"), true));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.executor("g/actual", Set.of("g/actual"), false));
        assertThrows(IllegalStateException.class, () -> ProofAssertions.identity("stable", "changed"));
    }
}
