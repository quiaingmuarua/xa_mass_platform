package com.xa.mass.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthPropertiesTest {

    @Test
    void blankModeDefaultsToSessionMode() {
        OperatorAuthProperties properties = new OperatorAuthProperties("", false);

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.SESSION);
        assertThat(properties.operatorHeadersEnabled()).isFalse();
    }

    @Test
    void explicitDevHeaderModeEnablesOperatorHeaders() {
        OperatorAuthProperties properties = new OperatorAuthProperties("dev-header", true);

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.DEV_HEADER);
        assertThat(properties.operatorHeadersEnabled()).isTrue();
    }

    @Test
    void devHeaderModeRequiresLocalFixtureOverride() {
        OperatorAuthProperties properties = new OperatorAuthProperties("dev-header", false);

        assertThatThrownBy(properties::validateStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mass.auth.operator.allow-local-fixture-header=true");
    }

    @Test
    void devHeaderModeAllowsExplicitLocalFixtureOverride() {
        OperatorAuthProperties properties = new OperatorAuthProperties("dev-header", true);

        properties.validateStartup();

        assertThat(properties.mode()).isEqualTo(OperatorAuthMode.DEV_HEADER);
    }

    @Test
    void springCanInstantiatePropertiesThroughAutowiredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(OperatorAuthProperties.class);
            context.refresh();

            OperatorAuthProperties properties = context.getBean(OperatorAuthProperties.class);

            assertThat(properties.mode()).isEqualTo(OperatorAuthMode.SESSION);
        }
    }

    @Test
    void invalidModeFailsFast() {
        OperatorAuthProperties properties = new OperatorAuthProperties(
                "header-admin",
                false
        );

        assertThatThrownBy(properties::mode)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported mass.auth.operator.mode");
    }
}
