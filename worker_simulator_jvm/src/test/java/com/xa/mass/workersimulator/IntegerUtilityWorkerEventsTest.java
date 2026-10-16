package com.xa.mass.workersimulator;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntegerUtilityWorkerEventsTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Map<String, Object> payload) throws Exception {
        var definitions = IntegerUtilityWorkerEvents.definitions();
        assertThat(definitions).extracting(WorkerEventDefinition::eventName)
                .containsExactly("extension.worker.integer.uint32-prime");
        var definition = (WorkerEventDefinition<Map<String, Object>>) definitions.getFirst();
        return Jsons.parseObject(definition.handler().execute(payload));
    }

    @Test void exactBoundariesAndOriginalInputAreRetained() throws Exception {
        for (String value : List.of("2", "3", "17", "017", "2147483647", "4294967291"))
            assertThat(execute(Map.of("value", value))).containsEntry("input", value)
                    .containsEntry("valid", true).containsEntry("isPrime", true).doesNotContainKey("error");
        for (String value : List.of("0", "1", "4", "9", "49", "4294836225", "4294967295"))
            assertThat(execute(Map.of("value", value))).containsEntry("valid", true).containsEntry("isPrime", false);
    }

    @Test void rejectsMalformedAndOutOfRangeInputsWithoutAnswer() throws Exception {
        for (Object value : List.of("", "-1", "+2", "2.0", "2e2", " 17", "１７", "4294967296", "00000000017", 17, true))
            assertThat(execute(Map.of("value", value))).containsEntry("valid", false)
                    .containsEntry("error", "INVALID_UINT32").doesNotContainKey("isPrime");
        assertThat(execute(Map.of())).containsEntry("valid", false).doesNotContainKey("isPrime");
    }

    @Test void agreesWithAnIndependentSieveAcrossSmallIntegers() throws Exception {
        boolean[] composite = new boolean[1000];
        composite[0] = composite[1] = true;
        for (int prime = 2; prime < composite.length; prime++)
            if (!composite[prime])
                for (int multiple = prime * 2; multiple < composite.length; multiple += prime) composite[multiple] = true;
        for (int value = 0; value < composite.length; value++)
            assertThat(execute(Map.of("value", Integer.toString(value))).get("isPrime")).as("value %s", value).isEqualTo(!composite[value]);
    }
}
