package com.xa.mass.api.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OperatorAuthProperties {

    private final String configuredMode;
    private final boolean allowLocalFixtureHeader;

    @Autowired
    public OperatorAuthProperties(@Value("${mass.auth.operator.mode:}") String configuredMode,
                                  @Value("${mass.auth.operator.allow-local-fixture-header:false}")
                                  boolean allowLocalFixtureHeader) {
        this.configuredMode = configuredMode;
        this.allowLocalFixtureHeader = allowLocalFixtureHeader;
    }

    private OperatorAuthProperties(OperatorAuthMode mode) {
        this.configuredMode = Objects.requireNonNull(mode, "mode").configValue();
        this.allowLocalFixtureHeader = mode == OperatorAuthMode.DEV_HEADER;
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
        return OperatorAuthMode.SESSION;
    }

    public boolean operatorHeadersEnabled() {
        return mode() == OperatorAuthMode.DEV_HEADER;
    }

    public boolean allowLocalFixtureHeader() {
        return allowLocalFixtureHeader;
    }

    public void validateStartup() {
        if (mode() == OperatorAuthMode.DEV_HEADER && !allowLocalFixtureHeader) {
            throw new IllegalStateException(
                    "mass.auth.operator.mode=dev-header requires "
                            + "mass.auth.operator.allow-local-fixture-header=true"
            );
        }
    }
}
