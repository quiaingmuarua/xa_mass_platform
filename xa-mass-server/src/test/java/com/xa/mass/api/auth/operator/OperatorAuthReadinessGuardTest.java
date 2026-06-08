package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.OperatorAuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthReadinessGuardTest {

    @Test
    void sessionModeRequiresActiveOperatorCredential() {
        InMemoryOperatorCredentialStore store = new InMemoryOperatorCredentialStore();
        OperatorAuthReadinessGuard guard = guard(properties("session"), store);

        assertThatThrownBy(() -> guard.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session auth requires at least one active operator credential");
    }

    @Test
    void sessionModePassesWithActiveCredential() {
        InMemoryOperatorCredentialStore store = new InMemoryOperatorCredentialStore();
        store.upsert(new OperatorCredentialRecord(
                "ops-admin",
                "{bcrypt}hash",
                null,
                OperatorCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));

        assertThatCode(() -> guard(properties("session"), store).run()).doesNotThrowAnyException();
    }

    @Test
    void disabledModeDoesNotRequireCredential() {
        assertThatCode(() -> guard(properties("disabled"), new InMemoryOperatorCredentialStore()).run())
                .doesNotThrowAnyException();
    }

    @Test
    void postSeedEmptyStoreStillFailsWhenBootstrapUnlockWasConfigured() {
        OperatorAuthReadinessGuard guard = new OperatorAuthReadinessGuard(
                properties("session"),
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

    private OperatorAuthProperties properties(String mode) {
        return new OperatorAuthProperties(mode, false);
    }
}
