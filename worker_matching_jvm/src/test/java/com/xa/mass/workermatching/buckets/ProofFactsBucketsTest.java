package com.xa.mass.workermatching.buckets;

import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProofFactsBucketsTest {
    private WorkerFacts facts(Map<String, Object> worker, Map<String, Object> platform) {
        return new WorkerFacts("w", "g", worker, platform);
    }
    @Test void literalValuesAndEveryCombinationAgreeWithoutWildcardOrDelimiterCollisions() {
        var values = List.of("A", "*", "~", "|", "A|yes|yes", "\"quoted\"", "中文", "\\");
        var allKeys = new HashSet<String>();
        for (String value : values) {
            String key = ProofFactsBuckets.bucketKey(facts(Map.of("proofPool", value, "proofTarget", "yes",
                    "convergenceSlot", value), Map.of("proofEnabled", "yes")));
            assertTrue(allKeys.add(key));
            var directory = ProofFactsBuckets.decodeKeys(Set.of(key));
            for (int fields = 0; fields < 8; fields++) {
                var input = new LinkedHashMap<String, String>();
                if ((fields & 1) != 0) input.put("proofPool", value);
                if ((fields & 2) != 0) input.put("proofTarget", "yes");
                if ((fields & 4) != 0) input.put("proofEnabled", "yes");
                assertEquals(Set.of(key), ProofFactsBuckets.matchingKeys(input, directory));
                if ((fields & 1) != 0) for (String other : values) if (!other.equals(value)) {
                    input.put("proofPool", other);
                    assertTrue(ProofFactsBuckets.matchingKeys(input, directory).isEmpty());
                }
            }
            assertEquals(Set.of(key), ProofFactsBuckets.matchingKeys(Map.of("convergenceSlot", value), directory));
            for (String other : values) if (!other.equals(value))
                assertTrue(ProofFactsBuckets.matchingKeys(Map.of("convergenceSlot", other), directory).isEmpty());
        }
    }
    @Test void missingFieldsAreNotSentinelStringsAndYesNoNormalizationIsUnchanged() {
        String missing = ProofFactsBuckets.bucketKey(facts(Map.of(), Map.of()));
        var directory = ProofFactsBuckets.decodeKeys(Set.of(missing));
        assertEquals(Set.of(missing), ProofFactsBuckets.matchingKeys(Map.of(), directory));
        assertEquals(Set.of(missing), ProofFactsBuckets.matchingKeys(Map.of("proofTarget", "no", "proofEnabled", "no"), directory));
        assertTrue(ProofFactsBuckets.matchingKeys(Map.of("proofTarget", "*"), directory).isEmpty());
        for (String value : List.of("*", "~", "|")) {
            assertTrue(ProofFactsBuckets.matchingKeys(Map.of("proofPool", value), directory).isEmpty());
            assertTrue(ProofFactsBuckets.matchingKeys(Map.of("convergenceSlot", value), directory).isEmpty());
        }
        assertEquals(missing, ProofFactsBuckets.bucketKey(facts(Map.of("proofPool", 1, "proofTarget", true),
                Map.of("proofEnabled", true))));
        assertNull(ProofFactsBuckets.bucketKey(null));
        assertNotEquals(missing, ProofFactsBuckets.bucketKey(facts(Map.of("proofPool", "null"), Map.of())));
    }
}
