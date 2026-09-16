package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RefillTargetTest {
    @Test void targetWireRemainsFlatAndQuantityDoesNotChangeQueryIdentity() {
        var mapper = JsonMapper.builder().build();
        var first = new RefillTarget(Map.of("worker.country", List.of("CN")), 10);
        assertEquals(first.query(), RefillTarget.of(first.query(), 100).query());
        assertEquals(mapper.readTree("{\"query\":{\"worker.country\":[\"CN\"]},\"count\":10}"),
                mapper.readTree(mapper.writeValueAsString(first)));
        assertEquals(first, mapper.readValue(mapper.writeValueAsString(first), RefillTarget.class));
        assertEquals(new EligibilityQuery(Map.of()), mapper.readValue("{\"count\":100}", RefillTarget.class).query());
        assertEquals(new RefillTarget(Map.of(), 100), new RefillTarget(null, 100));
    }
    @Test void rejectsInvalidCountsUnknownFieldsAndCoercion() {
        var mapper = JsonMapper.builder().build();
        for (String invalid : List.of("{}", "{\"count\":0}", "{\"count\":1001}", "{\"count\":1.0}",
                "{\"count\":\"1\"}", "{\"count\":1,\"extra\":true}",
                "{\"count\":1,\"query\":{\"a\":[1]}}", "{\"count\":1,\"query\":{\"a\":{\"op\":\"eq\",\"values\":[\"CN\"]}}}")) {
            assertThrows(RuntimeException.class, () -> mapper.readValue(invalid, RefillTarget.class), invalid);
        }
    }
}
