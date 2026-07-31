package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneNumberCapabilityTest {

    @Test
    void definitionsExposeThreeFocusedPhoneCapabilities()
            throws Exception {
        String workerId = "scenario-phone-number-worker-001";
        List<WorkerEventDefinition<Map<String, Object>>> definitions =
                PhoneNumberCapability.definitions(workerId);

        assertThat(definitions)
                .extracting(WorkerEventDefinition::eventCode)
                .containsExactlyInAnyOrderElementsOf(
                        PhoneNumberCapability.EVENT_CODES
                );

        Map<String, Object> e164 = execute(
                definitions,
                PhoneNumberCapability.E164_EVENT_CODE
        );
        assertCommonResult(e164, workerId);
        assertThat(e164).containsEntry("e164", "+41798765432");

        Map<String, Object> country = execute(
                definitions,
                PhoneNumberCapability.COUNTRY_EVENT_CODE
        );
        assertCommonResult(country, workerId);
        assertThat(country)
                .containsEntry("countryCallingCode", 41L)
                .containsEntry("regionCode", "CH")
                .containsEntry("country", "Switzerland");

        Map<String, Object> carrier = execute(
                definitions,
                PhoneNumberCapability.ORIGINAL_CARRIER_EVENT_CODE
        );
        assertCommonResult(carrier, workerId);
        assertThat(carrier)
                .containsEntry("originalCarrier", "Swisscom");
    }

    @Test
    void invalidNumberRemainsSuccessfulDomainOutputForEveryEvent()
            throws Exception {
        List<WorkerEventDefinition<Map<String, Object>>> definitions =
                PhoneNumberCapability.definitions(
                        "scenario-phone-number-worker-002"
                );

        for (WorkerEventDefinition<Map<String, Object>> definition
                : definitions) {
            Map<String, Object> result = Jsons.parseObject(
                    definition.handler().execute(
                            Map.of("rawNumber", "not-a-phone-number")
                    )
            );

            assertThat(result)
                    .containsEntry("possible", false)
                    .containsEntry("valid", false);
            assertThat((String) result.get("error"))
                    .startsWith("PARSE_");
            assertThat(result)
                    .doesNotContainKeys(
                            "e164",
                            "countryCallingCode",
                            "regionCode",
                            "country",
                            "originalCarrier"
                    );
        }
    }

    private static Map<String, Object> execute(
            List<WorkerEventDefinition<Map<String, Object>>> definitions,
            String eventCode
    ) throws Exception {
        WorkerEventDefinition<Map<String, Object>> definition =
                definitions.stream()
                        .filter(candidate -> candidate.eventCode()
                                .equals(eventCode))
                        .findFirst()
                        .orElseThrow();
        return Jsons.parseObject(
                definition.handler().execute(
                        Map.of(
                                "rawNumber",
                                "+41798765432",
                                "defaultRegion",
                                "CH"
                        )
                )
        );
    }

    private static void assertCommonResult(
            Map<String, Object> result,
            String workerId
    ) {
        assertThat(result)
                .containsEntry("input", "+41798765432")
                .containsEntry("workerId", workerId)
                .containsEntry("possible", true)
                .containsEntry("valid", true);
    }
}
