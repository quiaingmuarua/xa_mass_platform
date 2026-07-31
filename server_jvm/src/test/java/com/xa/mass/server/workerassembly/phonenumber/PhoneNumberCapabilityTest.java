package com.xa.mass.server.workerassembly.phonenumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneNumberCapabilityTest {

    @Test
    void builtInDefinitionUsesLibphonenumberMetadata()
            throws Exception {
        String encoded = PhoneNumberCapability
                .definition("phonenumber-worker-001")
                .handler()
                .execute(Map.of("rawNumber", "+41798765432"));

        Map<String, Object> result = Jsons.parseObject(encoded);

        assertThat(result)
                .containsEntry("input", "+41798765432")
                .containsEntry(
                        "workerId",
                        "phonenumber-worker-001"
                )
                .containsEntry("possible", true)
                .containsEntry("valid", true)
                .containsEntry("e164", "+41798765432")
                .containsEntry("regionCode", "CH")
                .containsEntry("country", "Switzerland")
                .containsEntry("numberType", "MOBILE")
                .containsEntry("originalCarrier", "Swisscom");
    }

    @Test
    void invalidNumberRemainsSuccessfulDomainOutput() {
        Map<String, Object> result = PhoneNumberCapability.lookup(
                "phonenumber-worker-002",
                Map.of("rawNumber", "not-a-phone-number")
        );

        assertThat(result)
                .containsEntry("possible", false)
                .containsEntry("valid", false);
        assertThat((String) result.get("error"))
                .startsWith("PARSE_");
    }
}
