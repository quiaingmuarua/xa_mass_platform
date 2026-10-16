package com.xa.mass.workersimulator;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BatchUtilityWorkerEventsTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String event, Map<String, Object> input) throws Exception {
        var definitions = new ArrayList<WorkerEventDefinition<?>>();
        definitions.addAll(StringUtilityWorkerEvents.definitions());
        definitions.addAll(PhoneNumberWorkerEvents.definitions());
        var definition = (WorkerEventDefinition<Map<String, Object>>) definitions.stream()
                .filter(value -> value.eventName().equals(event)).findFirst().orElseThrow();
        return Jsons.parseObject(definition.handler().execute(input));
    }

    @Test void computesEveryMd5InOneHandlerReturnWithIndependentUtf8Vectors() throws Exception {
        var actual = execute(StringUtilityWorkerEvents.MD5_BATCH_EVENT_CODE, Map.of("values", List.of("a", "abc", "中文", "")));
        assertThat(actual.get("results")).isEqualTo(List.of(
                Map.of("input", "a", "valid", true, "md5", "0cc175b9c0f1b6a831c399e269772661"),
                Map.of("input", "abc", "valid", true, "md5", "900150983cd24fb0d6963f7d28e17f72"),
                Map.of("input", "中文", "valid", true, "md5", "a7bac2239fcdcb3a067903d8077c4a07"),
                Map.of("input", "", "valid", true, "md5", "d41d8cd98f00b204e9800998ecf8427e")));
    }

    @Test void keepsCarrierBusinessNegativesAsPerNumberResults() throws Exception {
        var actual = execute(PhoneNumberWorkerEvents.ORIGINAL_CARRIER_BATCH_EVENT_CODE,
                Map.of("rawNumbers", List.of("+8613800000000", "+861065000000", "+86123")));
        assertThat(actual.get("results")).isEqualTo(List.of(
                Map.of("input", "+8613800000000", "possible", true, "valid", true, "originalCarrier", "China Mobile"),
                Map.of("input", "+861065000000", "possible", true, "valid", true, "originalCarrier", ""),
                Map.of("input", "+86123", "possible", false, "valid", false, "error", "INVALID_PHONE_NUMBER")));
    }

    @Test void arraysHaveTheirOwnBoundedAdmissionWithoutChangingSingleEvents() throws Exception {
        for (var definition : Map.of(StringUtilityWorkerEvents.MD5_BATCH_EVENT_CODE, "values",
                PhoneNumberWorkerEvents.ORIGINAL_CARRIER_BATCH_EVENT_CODE, "rawNumbers").entrySet()) {
            for (Object invalid : List.of("a", List.of(), List.of(1), Collections.nCopies(101, "+86123")))
                assertThatThrownBy(() -> execute(definition.getKey(), Map.of(definition.getValue(), invalid)))
                        .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execute(definition.getKey(), Map.of())).isInstanceOf(IllegalArgumentException.class);
            for (int size : List.of(1, 100)) {
                var result = execute(definition.getKey(), Map.of(definition.getValue(), Collections.nCopies(size, "+86123")));
                assertThat((List<?>) result.get("results")).hasSize(size);
            }
        }
        assertThat(execute(StringUtilityWorkerEvents.MD5_EVENT_CODE, Map.of("value", "a")))
                .containsEntry("md5", "0cc175b9c0f1b6a831c399e269772661").doesNotContainKey("results");
    }
}
