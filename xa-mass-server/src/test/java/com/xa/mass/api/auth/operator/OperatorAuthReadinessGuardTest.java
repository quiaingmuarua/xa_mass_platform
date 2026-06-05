package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.OperatorAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthReadinessGuardTest {

    @Test
    void prodSessionRequiresActiveOperatorCredential() {
        InMemoryOperatorCredentialStore store = new InMemoryOperatorCredentialStore();
        OperatorAuthReadinessGuard guard = guard(prodProperties(""), store);

        assertThatThrownBy(() -> guard.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod session auth requires at least one active operator credential");
    }

    @Test
    void prodSessionPassesWithActiveCredential() {
        InMemoryOperatorCredentialStore store = new InMemoryOperatorCredentialStore();
        store.upsert(new OperatorCredentialRecord(
                "ops-admin",
                "{bcrypt}hash",
                null,
                OperatorCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));

        assertThatCode(() -> guard(prodProperties(""), store).run()).doesNotThrowAnyException();
    }

    @Test
    void prodDisabledDoesNotRequireCredential() {
        assertThatCode(() -> guard(prodProperties("disabled"), new InMemoryOperatorCredentialStore()).run())
                .doesNotThrowAnyException();
    }

    @Test
    void postSeedEmptyStoreStillFailsWhenBootstrapUnlockWasConfigured() {
        OperatorAuthReadinessGuard guard = new OperatorAuthReadinessGuard(
                prodProperties(""),
                new InMemoryOperatorCredentialStore(),
                true,
                "apply",
                "classpath:operator-credentials.json",
                true
        );

        assertThatThrownBy(() -> guard.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed completed but no active operator credential exists");
    }

    private OperatorAuthReadinessGuard guard(OperatorAuthProperties properties,
                                             OperatorCredentialStore store) {
        return new OperatorAuthReadinessGuard(properties, store, false, "apply", "", false);
    }

    private OperatorAuthProperties prodProperties(String mode) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new OperatorAuthProperties(environment, mode, false);
    }
}
