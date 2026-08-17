package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringUtilityWorkerEventsTest {

    @Test
    void definitionsUseUtf8AndKnownDigestVectors() throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                StringUtilityWorkerEvents.definitions();

        assertThat(definitions)
                .extracting(WorkerEventDefinition::eventName)
                .containsExactly(
                        StringUtilityWorkerEvents.MD5_EVENT_CODE,
                        StringUtilityWorkerEvents.SHA1_EVENT_CODE,
                        StringUtilityWorkerEvents.BASE64_ENCODE_EVENT_CODE
                );
        assertThat(execute(
                definitions,
                StringUtilityWorkerEvents.MD5_EVENT_CODE,
                "abc"
        )).containsEntry(
                "md5",
                "900150983cd24fb0d6963f7d28e17f72"
        );
        assertThat(execute(
                definitions,
                StringUtilityWorkerEvents.SHA1_EVENT_CODE,
                "abc"
        )).containsEntry(
                "sha1",
                "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assertThat(execute(
                definitions,
                StringUtilityWorkerEvents.BASE64_ENCODE_EVENT_CODE,
                "hello"
        )).containsEntry("base64", "aGVsbG8=");
    }

    @Test
    void emptyStringIsValidAndMissingValueIsDomainError()
            throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                StringUtilityWorkerEvents.definitions();

        Map<String, Object> empty = execute(
                definitions,
                StringUtilityWorkerEvents.MD5_EVENT_CODE,
                ""
        );
        assertThat(empty)
                .containsEntry("valid", true)
                .containsEntry(
                        "md5",
                        "d41d8cd98f00b204e9800998ecf8427e"
                );

        for (WorkerEventDefinition<?> definition : definitions) {
            Map<String, Object> missing = Jsons.parseObject(
                    mapDefinition(definition).handler().execute(Map.of())
            );
            assertThat(missing)
                    .containsEntry("valid", false)
                    .containsEntry("error", "VALUE_STRING_REQUIRED")
                    .doesNotContainKey("workerId");
        }
    }

    private static Map<String, Object> execute(
            List<WorkerEventDefinition<?>> definitions,
            String eventCode,
            String value
    ) throws Exception {
        WorkerEventDefinition<?> definition = definitions.stream()
                .filter(candidate -> candidate.eventName().equals(eventCode))
                .findFirst()
                .orElseThrow();
        Map<String, Object> result = Jsons.parseObject(
                mapDefinition(definition).handler().execute(
                        Map.of("value", value)
                )
        );
        assertThat(result)
                .containsEntry("input", value)
                .containsEntry("valid", true)
                .doesNotContainKey("workerId");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static WorkerEventDefinition<Map<String, Object>> mapDefinition(
            WorkerEventDefinition<?> definition
    ) {
        return (WorkerEventDefinition<Map<String, Object>>) definition;
    }
}
