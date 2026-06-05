package com.xa.mass.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthPropertiesTest {

    @Test
    void devDefaultsToDevHeaderMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        OperatorAuthProperties properties = new OperatorAuthProperties(environment, "", false);

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.DEV_HEADER);
        assertThat(properties.operatorHeadersEnabled()).isTrue();
    }

    @Test
    void prodDefaultsToSessionMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        OperatorAuthProperties properties = new OperatorAuthProperties(environment, "", false);

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.SESSION);
        assertThat(properties.operatorHeadersEnabled()).isFalse();
    }

    @Test
    void prodRejectsDevHeaderModeWithoutUnsafeOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        OperatorAuthProperties properties = new OperatorAuthProperties(environment, "dev-header", false);

        assertThatThrownBy(properties::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mass.auth.operator.allow-unsafe-dev-header-in-prod=true");
    }

    @Test
    void prodAllowsDevHeaderModeOnlyWithUnsafeOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        OperatorAuthProperties properties = new OperatorAuthProperties(environment, "dev-header", true);

        properties.validateStartup();

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.DEV_HEADER);
    }

    @Test
    void springCanInstantiatePropertiesThroughAutowiredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.register(OperatorAuthProperties.class);
            context.refresh();

            OperatorAuthProperties properties = context.getBean(OperatorAuthProperties.class);

            assertThat(properties.mode()).isEqualTo(OperatorAuthMode.SESSION);
        }
    }

    @Test
    void invalidModeFailsFast() {
        OperatorAuthProperties properties = new OperatorAuthProperties(
                new MockEnvironment(),
                "header-admin",
                false
        );

        assertThatThrownBy(properties::mode)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported mass.auth.operator.mode");
    }
}
