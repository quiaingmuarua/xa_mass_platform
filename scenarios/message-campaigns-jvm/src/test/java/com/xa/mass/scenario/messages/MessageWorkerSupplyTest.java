package com.xa.mass.scenario.messages;

import com.xa.mass.workermatching.QualifiedCountryDefinition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MessageWorkerSupplyTest {
    @Test void startupDeclarationNeedsNoTaskServiceOrMatchingRuntime() {
        var definition = new MessageCampaignsScenarioConfiguration().messageWorkerQualification();
        assertThat(definition).isEqualTo(new QualifiedCountryDefinition("messaging",
                "worker.messaging.available", "worker.messaging.phone", "messaging.enabled", "true", "country"));
    }
}
