package com.xa.mass.workermatching.views;

import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProofFactsViewsTest {
    private WorkerFacts facts(Map<String, Object> worker, Map<String, Object> platform) {
        return new WorkerFacts("w", "g", worker, platform);
    }
    @Test void literalValuesAndEveryCombinationAgreeWithoutWildcardOrDelimiterCollisions() {
        var values = List.of("A", "*", "~", "|", "A|yes|yes", "\"quoted\"", "中文", "\\");
        for (String value : values) {
            var views = ProofFactsViews.memberships(facts(Map.of("proofPool", value, "proofTarget", "yes",
                    "convergenceSlot", value), Map.of("proofEnabled", "yes")));
            for (int fields = 0; fields < 8; fields++) {
                var input = new LinkedHashMap<String, String>();
                if ((fields & 1) != 0) input.put("proofPool", value);
                if ((fields & 2) != 0) input.put("proofTarget", "yes");
                if ((fields & 4) != 0) input.put("proofEnabled", "yes");
                assertTrue(ProofFactsViews.select(input).matches("w", views), input.toString());
                if ((fields & 1) != 0) for (String other : values) if (!other.equals(value)) {
                    input.put("proofPool", other);
                    assertFalse(ProofFactsViews.select(input).matches("w", views), input.toString());
                }
            }
            assertTrue(ProofFactsViews.select(Map.of("convergenceSlot", value)).matches("w", views));
            for (String other : values) if (!other.equals(value))
                assertFalse(ProofFactsViews.select(Map.of("convergenceSlot", other)).matches("w", views));
        }
    }
    @Test void missingFieldsAreNotSentinelStringsAndYesNoNormalizationIsUnchanged() {
        var missing = ProofFactsViews.memberships(facts(Map.of(), Map.of()));
        assertTrue(ProofFactsViews.select(Map.of()).matches("w", missing));
        assertTrue(ProofFactsViews.select(Map.of("proofTarget", "no", "proofEnabled", "no")).matches("w", missing));
        assertFalse(ProofFactsViews.select(Map.of("proofTarget", "*")).matches("w", missing));
        for (String value : List.of("*", "~", "|")) {
            assertFalse(ProofFactsViews.select(Map.of("proofPool", value)).matches("w", missing));
            assertFalse(ProofFactsViews.select(Map.of("convergenceSlot", value)).matches("w", missing));
        }
        var nonString = ProofFactsViews.memberships(facts(Map.of("proofPool", 1, "proofTarget", true),
                Map.of("proofEnabled", true)));
        assertEquals(missing, nonString);
        assertNull(ProofFactsViews.memberships(null));
    }
}
