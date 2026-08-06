package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class AndroidWorkerPropertiesFingerprintTest {

    @Test
    public void objectKeyOrderIsCanonicalButArrayOrderIsRetained() {
        Map<String, Object> firstNested = new LinkedHashMap<>();
        firstNested.put("z", 1);
        firstNested.put("a", true);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("nested", firstNested);
        first.put("items", List.of("a", "b"));

        Map<String, Object> secondNested = new LinkedHashMap<>();
        secondNested.put("a", true);
        secondNested.put("z", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of("a", "b"));
        second.put("nested", secondNested);

        assertEquals(
                AndroidWorkerPropertiesFingerprint.sha256(first),
                AndroidWorkerPropertiesFingerprint.sha256(second)
        );

        second.put("items", List.of("b", "a"));
        assertNotEquals(
                AndroidWorkerPropertiesFingerprint.sha256(first),
                AndroidWorkerPropertiesFingerprint.sha256(second)
        );
    }
}
