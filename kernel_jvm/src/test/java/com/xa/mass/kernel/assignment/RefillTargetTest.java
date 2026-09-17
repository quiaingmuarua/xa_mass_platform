package com.xa.mass.kernel.assignment;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RefillTargetTest {
    @Test void capturesPoolAndTargetWithIndependentQuantityAndStrictJson() {
        var values = new ArrayList<>(List.of("CN"));
        var first = new RefillTarget("country", new EligibilityQuery(Map.of("worker.country",values)),10);
        values.clear();
        assertEquals(List.of("CN"), first.target().query().get("worker.country"));
        assertEquals(first.target(), new RefillTarget("country",first.target(),100).target());
        assertNotEquals(first,new RefillTarget("country",first.target(),100));
        assertNotEquals(first,new RefillTarget("other",first.target(),10));
        var mapper=JsonMapper.builder().build();
        assertEquals(first,mapper.readValue(mapper.writeValueAsString(first),RefillTarget.class));
        assertEquals(Set.of("poolName","target","count"),mapper.readValue(mapper.writeValueAsString(first),Map.class).keySet());
    }
    @Test void rejectsLegacyMissingNullCoercedAndInvalidValues() {
        var mapper=JsonMapper.builder().build();
        for (String invalid : List.of(
                "{}", "{\"query\":{},\"count\":100}",
                "{\"poolName\":\"p\",\"count\":1}",
                "{\"poolName\":\"p\",\"target\":null,\"count\":1}",
                "{\"poolName\":\"p\",\"target\":{},\"count\":\"1\"}",
                "{\"poolName\":\"p\",\"target\":{},\"count\":1.0}",
                "{\"poolName\":\"p\",\"target\":{},\"count\":0}",
                "{\"poolName\":\"p\",\"target\":{},\"count\":1001}",
                "{\"poolName\":\"p\",\"target\":{\"country\":[1]},\"count\":1}",
                "{\"poolName\":\"p\",\"target\":{},\"count\":1,\"extra\":true}")) {
            assertThrows(RuntimeException.class,()->mapper.readValue(invalid,RefillTarget.class),invalid);
        }
        assertThrows(IllegalArgumentException.class,()->new RefillTarget(" ",new EligibilityQuery(Map.of()),1));
        assertThrows(NullPointerException.class,()->new RefillTarget("p",null,1));
    }
}
