package com.xa.mass.server.bootstrap.seed;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneSeedImportConfigurationTest {

    @Test
    void prodProfileDisablesDevOnlyApiKeyRawSecretSeeds() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThat(ControlPlaneSeedImportConfiguration.allowDevOnlyApiKeyRawSecrets(environment)).isFalse();
    }

    @Test
    void devProfileAllowsDevOnlyApiKeyRawSecretSeeds() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThat(ControlPlaneSeedImportConfiguration.allowDevOnlyApiKeyRawSecrets(environment)).isTrue();
    }
}
