package com.xa.mass.api.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OperatorAuthStartupGuard implements InitializingBean {

    private final OperatorAuthProperties properties;

    @Autowired
    public OperatorAuthStartupGuard(OperatorAuthProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void afterPropertiesSet() {
        properties.validateStartup();
    }
}
