package com.xa.mass.api.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

@Component
public class OperatorAuthProperties {

    private final Environment environment;
    private final String configuredMode;
    private final boolean allowUnsafeDevHeaderInProd;

    @Autowired
    public OperatorAuthProperties(Environment environment,
                                  @Value("${mass.auth.operator.mode:}") String configuredMode,
                                  @Value("${mass.auth.operator.allow-unsafe-dev-header-in-prod:false}")
                                  boolean allowUnsafeDevHeaderInProd) {
        this.environment = environment;
        this.configuredMode = configuredMode;
        this.allowUnsafeDevHeaderInProd = allowUnsafeDevHeaderInProd;
    }

    private OperatorAuthProperties(OperatorAuthMode mode) {
        this.environment = null;
        this.configuredMode = Objects.requireNonNull(mode, "mode").configValue();
        this.allowUnsafeDevHeaderInProd = true;
    }

    public static OperatorAuthProperties devHeaderForTests() {
        return new OperatorAuthProperties(OperatorAuthMode.DEV_HEADER);
    }

    public static OperatorAuthProperties sessionForTests() {
        return new OperatorAuthProperties(OperatorAuthMode.SESSION);
    }

    public static OperatorAuthProperties disabledForTests() {
        return new OperatorAuthProperties(OperatorAuthMode.DISABLED);
    }

    public OperatorAuthMode mode() {
        if (configuredMode != null && !configuredMode.isBlank()) {
            return OperatorAuthMode.parse(configuredMode);
        }
        return isProdProfile() ? OperatorAuthMode.SESSION : OperatorAuthMode.DEV_HEADER;
    }

    public boolean operatorHeadersEnabled() {
        return mode() == OperatorAuthMode.DEV_HEADER;
    }

    public boolean allowUnsafeDevHeaderInProd() {
        return allowUnsafeDevHeaderInProd;
    }

    public boolean isProdProfile() {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        String[] effectiveProfiles = activeProfiles.length == 0 ? environment.getDefaultProfiles() : activeProfiles;
        return Arrays.asList(effectiveProfiles).contains("prod");
    }

    public void validateStartup() {
        if (isProdProfile() && mode() == OperatorAuthMode.DEV_HEADER && !allowUnsafeDevHeaderInProd) {
            throw new IllegalStateException(
                    "prod requires mass.auth.operator.mode=session or disabled; dev-header requires "
                            + "mass.auth.operator.allow-unsafe-dev-header-in-prod=true"
            );
        }
    }
}
