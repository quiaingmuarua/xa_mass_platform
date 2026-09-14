package com.xa.mass.workermatching;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleHandlerTest {
    @Test void messagingUsesPhonePartitionAndCountryBoundsTogether() {
        var selector=TaskItemWorkerSelector.parse(Map.of(
                "worker.country",Map.of("op","eq","values",List.of("CN")),
                "worker.phone",Map.of("op","eq","values",List.of("+86123"))));
        var query=RuleHandler.MESSAGING.criteria(RuleHandler.normalize(selector,1).query());
        assertEquals("phone:+86123",query.partition());
        assertEquals("countries",query.kind());
        assertEquals(List.of(Integer.toString(CountryIndex.code("CN"))),query.values());
        assertThrows(IllegalArgumentException.class,()->RuleHandler.COUNTRY.criteria(RuleHandler.normalize(selector,1).query()));
    }
    @Test void namedRulesKeepAnyAndExplicitIdsInsideTheirIndex() {
        for (var handler:RuleHandler.values()) {
            assertEquals("any",handler.criteria(Map.of()).kind());
            assertEquals("ids",handler.criteria(Map.of("workerId",List.of("w"))).kind());
        }
    }
    @Test void unknownFieldsAndIdentityPropertyMixturesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->TaskItemWorkerSelector.parse(Map.of(
                "workerId",List.of("w"),"worker.country",Map.of("op","eq","values",List.of("CN")))));
        assertThrows(IllegalArgumentException.class,()->RuleHandler.MESSAGING.criteria(Map.of("worker.unknown",List.of("x"))));
    }
}
