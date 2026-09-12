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
        var query=RuleHandler.MESSAGING.criteria(selector);
        assertEquals("phone:+86123",query.partition());
        assertEquals("countries",query.kind());
        assertEquals(List.of(Integer.toString(CountryIndex.code("CN"))),query.values());
        assertThrows(IllegalArgumentException.class,()->RuleHandler.COUNTRY.criteria(selector));
    }
    @Test void namedRulesKeepAnyAndExplicitIdsInsideTheirIndex() {
        for (var handler:RuleHandler.values()) {
            var query=RuleIndex.query(()->{throw new AssertionError("unexpected read");},"index",handler::criteria);
            assertFalse(query.usesIdentitySelection(TaskItemWorkerSelector.parse(Map.of())));
            assertFalse(query.usesIdentitySelection(TaskItemWorkerSelector.parse(Map.of("workerId",List.of("w")))));
        }
    }
    @Test void unknownFieldsAndIdentityPropertyMixturesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->TaskItemWorkerSelector.parse(Map.of(
                "workerId",List.of("w"),"worker.country",Map.of("op","eq","values",List.of("CN")))));
        assertThrows(IllegalArgumentException.class,()->RuleHandler.MESSAGING.criteria(TaskItemWorkerSelector.parse(
                Map.of("worker.unknown",Map.of("op","eq","values",List.of("x"))))));
    }
}
