package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneNumberWorkerEventsTest {

    @Test
    void definitionsExposeThreeWorkerIndependentEvents() throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                PhoneNumberWorkerEvents.definitions();

        assertThat(definitions)
                .extracting(WorkerEventDefinition::eventName)
                .containsExactly(
                        PhoneNumberWorkerEvents.E164_EVENT_CODE,
                        PhoneNumberWorkerEvents.COUNTRY_EVENT_CODE,
                        PhoneNumberWorkerEvents.ORIGINAL_CARRIER_EVENT_CODE
                );

        Map<String, Object> e164 = execute(
                definitions,
                PhoneNumberWorkerEvents.E164_EVENT_CODE
        );
        assertCommonResult(e164);
        assertThat(e164).containsEntry("e164", "+41798765432");

        Map<String, Object> country = execute(
                definitions,
                PhoneNumberWorkerEvents.COUNTRY_EVENT_CODE
        );
        assertCommonResult(country);
        assertThat(country)
                .containsEntry("countryCallingCode", 41L)
                .containsEntry("regionCode", "CH")
                .containsEntry("country", "Switzerland");

        Map<String, Object> carrier = execute(
                definitions,
                PhoneNumberWorkerEvents.ORIGINAL_CARRIER_EVENT_CODE
        );
        assertCommonResult(carrier);
        assertThat(carrier)
                .containsEntry("originalCarrier", "Swisscom");
    }

    @Test
    void invalidNumberRemainsSuccessfulDomainOutputForEveryEvent()
            throws Exception {
        for (WorkerEventDefinition<?> definition
                : PhoneNumberWorkerEvents.definitions()) {
            Map<String, Object> result = Jsons.parseObject(
                    mapDefinition(definition).handler().execute(
                            Map.of("rawNumber", "not-a-phone-number")
                    )
            );

            assertThat(result)
                    .containsEntry("possible", false)
                    .containsEntry("valid", false)
                    .doesNotContainKey("workerId");
            assertThat((String) result.get("error"))
                    .startsWith("PARSE_");
        }
    }

    private static Map<String, Object> execute(
            List<WorkerEventDefinition<?>> definitions,
            String eventCode
    ) throws Exception {
        WorkerEventDefinition<?> definition = definitions.stream()
                .filter(candidate -> candidate.eventName().equals(eventCode))
                .findFirst()
                .orElseThrow();
        return Jsons.parseObject(
                mapDefinition(definition).handler().execute(
                        Map.of(
                                "rawNumber",
                                "+41798765432",
                                "defaultRegion",
                                "CH"
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static WorkerEventDefinition<Map<String, Object>> mapDefinition(
            WorkerEventDefinition<?> definition
    ) {
        return (WorkerEventDefinition<Map<String, Object>>) definition;
    }

    private static void assertCommonResult(Map<String, Object> result) {
        assertThat(result)
                .containsEntry("input", "+41798765432")
                .containsEntry("possible", true)
                .containsEntry("valid", true)
                .doesNotContainKey("workerId");
    }
}
