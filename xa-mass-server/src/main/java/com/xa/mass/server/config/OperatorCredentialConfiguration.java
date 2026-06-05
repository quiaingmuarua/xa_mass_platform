package com.xa.mass.server.config;

import com.xa.mass.api.auth.OperatorAuthProperties;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.operator.InMemoryOperatorSessionStore;
import com.xa.mass.api.auth.operator.OperatorCredentialStore;
import com.xa.mass.api.auth.operator.OperatorCredentialVerifier;
import com.xa.mass.api.auth.operator.OperatorSessionService;
import com.xa.mass.api.auth.operator.OperatorSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class OperatorCredentialConfiguration {

    @Bean
    public PasswordEncoder operatorPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public OperatorCredentialVerifier operatorCredentialVerifier(OperatorCredentialStore credentialStore,
                                                                 UserRolePermissionStore userStore,
                                                                 PasswordEncoder operatorPasswordEncoder) {
        return new OperatorCredentialVerifier(credentialStore, userStore, operatorPasswordEncoder);
    }

    @Bean
    public OperatorSessionStore operatorSessionStore() {
        return new InMemoryOperatorSessionStore();
    }

    @Bean
    public OperatorSessionService operatorSessionService(OperatorSessionStore sessionStore,
                                                         OperatorAuthProperties authProperties,
                                                         @Value("${mass.auth.operator.session.ttl:8h}") String ttl,
                                                         @Value("${mass.auth.operator.session.cookie-secure:}")
                                                         String configuredCookieSecure) {
        return new OperatorSessionService(sessionStore, authProperties, ttl, configuredCookieSecure);
    }
}
