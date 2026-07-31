package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringUtilityCapabilityTest {

    @Test
    void definitionsUseUtf8AndKnownDigestVectors()
            throws Exception {
        String workerId = "scenario-string-utils-worker-001";
        List<WorkerEventDefinition<Map<String, Object>>> definitions =
                StringUtilityCapability.definitions(workerId);

        assertThat(definitions)
                .extracting(WorkerEventDefinition::eventCode)
                .containsExactlyInAnyOrderElementsOf(
                        StringUtilityCapability.EVENT_CODES
                );
        assertThat(execute(
                definitions,
                StringUtilityCapability.MD5_EVENT_CODE,
                "abc"
        )).containsEntry(
                "md5",
                "900150983cd24fb0d6963f7d28e17f72"
        );
        assertThat(execute(
                definitions,
                StringUtilityCapability.SHA1_EVENT_CODE,
                "abc"
        )).containsEntry(
                "sha1",
                "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assertThat(execute(
                definitions,
                StringUtilityCapability.BASE64_ENCODE_EVENT_CODE,
                "hello"
        )).containsEntry("base64", "aGVsbG8=");
        assertThat(execute(
                definitions,
                StringUtilityCapability.BASE64_ENCODE_EVENT_CODE,
                "你好"
        )).containsEntry("base64", "5L2g5aW9");
    }

    @Test
    void emptyStringIsValidAndMissingValueIsDomainError()
            throws Exception {
        List<WorkerEventDefinition<Map<String, Object>>> definitions =
                StringUtilityCapability.definitions(
                        "scenario-string-utils-worker-002"
                );

        Map<String, Object> empty = execute(
                definitions,
                StringUtilityCapability.MD5_EVENT_CODE,
                ""
        );
        assertThat(empty)
                .containsEntry("valid", true)
                .containsEntry(
                        "md5",
                        "d41d8cd98f00b204e9800998ecf8427e"
                );

        for (WorkerEventDefinition<Map<String, Object>> definition
                : definitions) {
            Map<String, Object> missing = Jsons.parseObject(
                    definition.handler().execute(Map.of())
            );
            assertThat(missing)
                    .containsEntry("valid", false)
                    .containsEntry(
                            "error",
                            "VALUE_STRING_REQUIRED"
                    );
            Map<String, Object> nonString = Jsons.parseObject(
                    definition.handler().execute(
                            Map.of("value", 42)
                    )
            );
            assertThat(nonString)
                    .containsEntry("input", 42L)
                    .containsEntry("valid", false)
                    .containsEntry(
                            "error",
                            "VALUE_STRING_REQUIRED"
                    );
        }
    }

    private static Map<String, Object> execute(
            List<WorkerEventDefinition<Map<String, Object>>> definitions,
            String eventCode,
            String value
    ) throws Exception {
        WorkerEventDefinition<Map<String, Object>> definition =
                definitions.stream()
                        .filter(candidate -> candidate.eventCode()
                                .equals(eventCode))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> result = Jsons.parseObject(
                definition.handler().execute(Map.of("value", value))
        );
        assertThat(result)
                .containsEntry("input", value)
                .containsEntry("valid", true);
        return result;
    }
}
