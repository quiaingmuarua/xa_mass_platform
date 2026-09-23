package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerPropertiesContractTest {
    @Test
    void flatPropertiesAreCapturedWithoutCoercionOrPathInterpretation() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("network.type", "wifi");
        source.put("empty", "");
        var captured = WorkerDeliveryCodec.copyWorkerProperties(source);
        source.clear();
        assertEquals(Map.of("network.type", "wifi", "empty", ""), captured);
        assertThrows(UnsupportedOperationException.class, captured::clear);
        assertEquals(List.of("empty", "network.type"), List.copyOf(captured.keySet()));
        assertTrue(WorkerDeliveryCodec.copyWorkerProperties(Map.of()).isEmpty());
    }

    @Test
    void invalidKeysValuesAndNestedValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkerDeliveryCodec.copyWorkerProperties(null));
        for (Object invalid : List.of(87, true, List.of("x"), Map.of("x", "y"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkerDeliveryCodec.copyWorkerProperties(Map.of("value", invalid)));
        }
        for (Object key : List.of("", " \t", 1)) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkerDeliveryCodec.copyWorkerProperties(Map.of(key, "value")));
        }
        Map<Object, Object> invalid = new LinkedHashMap<>();
        invalid.put("value", null);
        assertThrows(IllegalArgumentException.class, () -> WorkerDeliveryCodec.copyWorkerProperties(invalid));
        invalid.clear();
        invalid.put(null, "value");
        assertThrows(IllegalArgumentException.class, () -> WorkerDeliveryCodec.copyWorkerProperties(invalid));
    }
}
