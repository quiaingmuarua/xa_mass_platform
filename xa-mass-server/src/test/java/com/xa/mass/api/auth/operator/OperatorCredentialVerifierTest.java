package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserRecord;
import com.xa.mass.api.auth.iam.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorCredentialVerifierTest {

    @Test
    void verifiesActiveUserWithMatchingHash() {
        InMemoryUserRolePermissionStore userStore = InMemoryUserRolePermissionStore.bootstrapDefaults();
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        credentialStore.upsert(new OperatorCredentialRecord(
                "ops-admin",
                encoder.encode("secret"),
                null,
                OperatorCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
        OperatorCredentialVerifier verifier = new OperatorCredentialVerifier(
                credentialStore,
                userStore,
                encoder
        );

        assertThat(verifier.verify("ops-admin", "secret")).isTrue();
        assertThat(verifier.verify("ops-admin", "wrong")).isFalse();
    }

    @Test
    void rejectsDisabledUserOrCredential() {
        InMemoryUserRolePermissionStore userStore = InMemoryUserRolePermissionStore.bootstrapDefaults();
        var disabled = new UserRecord(
                "disabled-operator",
                "Disabled Operator",
                "disabled@example.internal",
                UserStatus.DISABLED,
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        userStore.createUser(disabled);
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        credentialStore.upsert(new OperatorCredentialRecord(
                "disabled-operator",
                encoder.encode("secret"),
                null,
                OperatorCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
        credentialStore.upsert(new OperatorCredentialRecord(
                "ops-admin",
                encoder.encode("secret"),
                null,
                OperatorCredentialStatus.DISABLED,
                Instant.now(),
                Instant.now()
        ));
        OperatorCredentialVerifier verifier = new OperatorCredentialVerifier(
                credentialStore,
                userStore,
                encoder
        );

        assertThat(verifier.verify("disabled-operator", "secret")).isFalse();
        assertThat(verifier.verify("ops-admin", "secret")).isFalse();
    }
}
