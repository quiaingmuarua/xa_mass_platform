package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

public final class OperatorCredentialVerifier {

    private final OperatorCredentialStore credentialStore;
    private final UserRolePermissionStore userStore;
    private final PasswordEncoder passwordEncoder;

    public OperatorCredentialVerifier(OperatorCredentialStore credentialStore,
                                      UserRolePermissionStore userStore,
                                      PasswordEncoder passwordEncoder) {
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.userStore = Objects.requireNonNull(userStore, "userStore");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
    }

    public boolean verify(String userId, String password) {
        if (userId == null || userId.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        String normalizedUserId = userId.trim();
        var user = userStore.getUser(normalizedUserId);
        if (user == null || user.status() != UserStatus.ACTIVE) {
            return false;
        }
        OperatorCredentialRecord credential = credentialStore.get(normalizedUserId);
        if (credential == null || !credential.active()) {
            return false;
        }
        return passwordEncoder.matches(password, credential.passwordHash());
    }
}
