package com.xa.mass.api.auth.operator;

import com.xa.mass.api.auth.OperatorAuthMode;
import com.xa.mass.api.auth.OperatorAuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Order(1)
public final class OperatorAuthReadinessGuard implements CommandLineRunner {

    private final OperatorAuthProperties authProperties;
    private final OperatorCredentialStore credentialStore;
    private final boolean seedEnabled;
    private final String seedMode;
    private final String operatorCredentialsLocation;
    private final boolean allowEmptyBeforeSeed;

    @Autowired
    public OperatorAuthReadinessGuard(OperatorAuthProperties authProperties,
                                      OperatorCredentialStore credentialStore,
                                      @Value("${mass.control-plane.seed.enabled:false}") boolean seedEnabled,
                                      @Value("${mass.control-plane.seed.mode:apply}") String seedMode,
                                      @Value("${mass.control-plane.seed.operator-credentials-location:}")
                                      String operatorCredentialsLocation,
                                      @Value("${mass.auth.operator.bootstrap.allow-empty-before-seed:false}")
                                      boolean allowEmptyBeforeSeed) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.seedEnabled = seedEnabled;
        this.seedMode = seedMode == null ? "" : seedMode.trim();
        this.operatorCredentialsLocation = operatorCredentialsLocation == null
                ? ""
                : operatorCredentialsLocation.trim();
        this.allowEmptyBeforeSeed = allowEmptyBeforeSeed;
    }

    @Override
    public void run(String... args) {
        if (authProperties.mode() != OperatorAuthMode.SESSION) {
            return;
        }
        if (credentialStore.hasActiveCredential()) {
            return;
        }
        if (allowEmptyBeforeSeed && seedEnabled && "apply".equalsIgnoreCase(seedMode)
                && !operatorCredentialsLocation.isBlank()) {
            throw new IllegalStateException(
                    "session auth seed completed but no active operator credential exists; "
                            + "check mass.control-plane.seed.operator-credentials-location"
            );
        }
        throw new IllegalStateException(
                "session auth requires at least one active operator credential; configure "
                        + "mass.control-plane.seed.operator-credentials-location or pre-seed xa_operator_credential"
        );
    }
}
