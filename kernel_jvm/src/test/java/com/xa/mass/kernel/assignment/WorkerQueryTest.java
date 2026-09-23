package com.xa.mass.kernel.assignment;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WorkerQueryTest {
    final JsonMapper json=JsonMapper.builder().build();

    @Test void capturesNativeJsonDeeplyWithoutInterpretingItsShape() {
        var nested=new ArrayList<Object>(Arrays.asList(7,true,null,"7"));
        var source=new LinkedHashMap<String,Object>(); source.put("arbitrary",nested);
        var query=new WorkerQuery("custom",source);
        nested.clear(); source.clear();
        assertEquals(Map.of("arbitrary",Arrays.asList(7,true,null,"7")),query.input());
        assertThrows(UnsupportedOperationException.class,()->((Map<?,?>)query.input()).clear());
        assertThrows(UnsupportedOperationException.class,()->((List<?>)((Map<?,?>)query.input()).get("arbitrary")).clear());
        for(Object input:List.of("text",7,true,new BigDecimal("1.25"),List.of(1,2),Map.of())) {
            var value=new WorkerQuery("anything",input);
            assertEquals(json.writeValueAsString(value),json.writeValueAsString(json.readValue(json.writeValueAsString(value),WorkerQuery.class)));
        }
    }

    @Test void strictEnvelopeRejectsOldShapesAndNeverCoercesNameOrNull() {
        for(String raw:List.of("{}","{\"workerId\":[\"w\"]}",
                "{\"executorName\":12,\"input\":{}}","{\"executorName\":\"f\"}",
                "{\"executorName\":\"f\",\"input\":null}",
                "{\"executorName\":\"f\",\"input\":{},\"extra\":0}"))
            assertThrows(RuntimeException.class,()->json.readValue(raw,WorkerQuery.class),raw);
        assertEquals(7, json.readValue("{\"executorName\":\"f\",\"input\":7}",WorkerQuery.class).input());
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery(" ",Map.of()));
        for(Object value:List.of(new Object(),new int[]{1},Set.of(1),Double.NaN,Double.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Map.of(1,"x")))
            assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f",value));
    }

    @Test void exactSizeDepthAndMemberBoundsApplyToInputIncludingEscapesAndUtf8() {
        assertDoesNotThrow(()->new WorkerQuery("f",Collections.nCopies(100,1)));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f",Collections.nCopies(101,1)));
        Object nested=1;
        for(int i=0;i<8;i++)nested=List.of(nested);
        var atLimit=nested; assertDoesNotThrow(()->new WorkerQuery("f",atLimit));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f",List.of(atLimit)));
        assertDoesNotThrow(()->new WorkerQuery("f","a".repeat(65534)));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f","a".repeat(65535)));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f","中".repeat(21845)));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f","\n".repeat(32768)));
        assertThrows(IllegalArgumentException.class,()->new WorkerQuery("f",Collections.nCopies(100,"x".repeat(700))));
    }
}
