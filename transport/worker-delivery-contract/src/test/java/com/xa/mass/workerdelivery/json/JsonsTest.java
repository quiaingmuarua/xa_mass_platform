package com.xa.mass.workerdelivery.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonsTest {

    @Test
    void parsesJsonIntoOrderedJdkValues() {
        Map<String, Object> parsed = Jsons.parseObject(
                "{\"text\":\"你好\",\"count\":3,\"ratio\":1.25,"
                        + "\"enabled\":true,\"missing\":null,"
                        + "\"items\":[\"a\",2]}"
        );

        assertEquals(
                List.of(
                        "text",
                        "count",
                        "ratio",
                        "enabled",
                        "missing",
                        "items"
                ),
                List.copyOf(parsed.keySet())
        );
        assertEquals("你好", parsed.get("text"));
        assertEquals(3L, parsed.get("count"));
        assertEquals(new BigDecimal("1.25"), parsed.get("ratio"));
        assertEquals(true, parsed.get("enabled"));
        assertEquals(null, parsed.get("missing"));
        assertEquals(List.of("a", 2L), parsed.get("items"));
    }

    @Test
    void writesCompactJsonInMapOrderAndPreservesNull() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("message", "<ok>");
        value.put("items", List.of(1, "two"));
        value.put("missing", null);

        assertEquals(
                "{\"message\":\"<ok>\",\"items\":[1,\"two\"],"
                        + "\"missing\":null}",
                Jsons.toJson(value)
        );
    }

    @Test
    void rejectsMalformedNonObjectAndUnsupportedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.parseObject("{bad-json")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.parseObject("{'value':1}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.parseObject("{\"value\":1} trailing")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.parseObject("[1]")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.toJson(new Object())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.toJson(Map.of(1, "value"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Jsons.toJson(Double.NaN)
        );
    }

    @Test
    void largeIntegralNumbersRemainLossless() {
        Object value = Jsons.parseObject(
                "{\"value\":9223372036854775808}"
        ).get("value");

        assertInstanceOf(BigDecimal.class, value);
        assertEquals(
                "9223372036854775808",
                Jsons.toJson(Map.of("value", value))
                        .replace("{\"value\":", "")
                        .replace("}", "")
        );
    }
}
