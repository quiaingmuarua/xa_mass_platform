package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerEventParameterResolversTest {

    @Test
    void stringResolverPreservesTheOriginalPayload()
            throws Exception {
        String payload = "{\"value\":\"测试\"}";

        assertEquals(
                payload,
                WorkerEventParameterResolvers.string()
                        .resolve(payload)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventParameterResolvers.string()
                        .resolve(null)
        );
    }

    @Test
    void jsonMapResolverReturnsJdkJsonValues()
            throws Exception {
        Map<String, Object> values =
                WorkerEventParameterResolvers.jsonMap().resolve(
                        "{\"value\":\"测试\",\"empty\":null}"
                );

        assertEquals("测试", values.get("value"));
        assertNull(values.get("empty"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventParameterResolvers.jsonMap()
                        .resolve("{bad-json")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerEventParameterResolvers.jsonMap()
                        .resolve(null)
        );
    }
}
