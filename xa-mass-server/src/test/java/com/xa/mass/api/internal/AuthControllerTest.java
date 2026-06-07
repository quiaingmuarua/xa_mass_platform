package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.OperatorAuthProperties;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.operator.InMemoryOperatorCredentialStore;
import com.xa.mass.api.auth.operator.InMemoryOperatorSessionStore;
import com.xa.mass.api.auth.operator.OperatorCredentialRecord;
import com.xa.mass.api.auth.operator.OperatorCredentialStatus;
import com.xa.mass.api.auth.operator.OperatorCredentialVerifier;
import com.xa.mass.api.auth.operator.OperatorSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(ApiAuthTestSupport.defaultOperatorAuthService())).build();
    }

    @Test
    void meReturnsViewerUserFromHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("ops-viewer"))
                .andExpect(jsonPath("$.data.permissions[0]").value("task:view"));
    }

    @Test
    void meReturnsCustomOperatorPrincipalFromHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "alice")
                        .header(ApiAuthService.USER_NAME_HEADER, "Alice Ops")
                        .header(ApiAuthService.USER_EMAIL_HEADER, "alice@example.test")
                        .header(ApiAuthService.USER_ROLES_HEADER, "OPS_CUSTOM")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, "task:view,worker:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("alice"))
                .andExpect(jsonPath("$.data.name").value("Alice Ops"))
                .andExpect(jsonPath("$.data.email").value("alice@example.test"))
                .andExpect(jsonPath("$.data.roles[0]").value("OPS_CUSTOM"))
                .andExpect(jsonPath("$.data.permissions[0]").value("task:view"))
                .andExpect(jsonPath("$.data.permissions[1]").value("worker:view"));
    }

    @Test
    void configReturnsDevHeaderModeByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.authMode").value("dev-header"))
                .andExpect(jsonPath("$.data.operatorHeaderSupported").value(true))
                .andExpect(jsonPath("$.data.sessionCookieSupported").value(false))
                .andExpect(jsonPath("$.data.csrfHeaderName").doesNotExist());
    }

    @Test
    void configReturnsSessionModeWithoutCredentialMaterial() throws Exception {
        MockMvc sessionMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(
                        ApiAuthTestSupport.sessionOperatorAuthService(),
                        OperatorAuthProperties.sessionForTests()
                )).build();

        sessionMvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.authMode").value("session"))
                .andExpect(jsonPath("$.data.operatorHeaderSupported").value(false))
                .andExpect(jsonPath("$.data.sessionCookieSupported").value(true))
                .andExpect(jsonPath("$.data.csrfHeaderName").value("X-Mass-Csrf-Token"))
                .andExpect(jsonPath("$.data.passwordPolicy").doesNotExist())
                .andExpect(jsonPath("$.data.sessionToken").doesNotExist());
    }

    @Test
    void logoutAcknowledgesAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("ops-admin"));
    }

    @Test
    void sessionLoginSetsCookieAndAllowsCurrentUserThenLogout() throws Exception {
        SessionFixture fixture = sessionFixture();
        MockMvc sessionMvc = fixture.mockMvc();

        var login = sessionMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userId": "ops-admin",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.id").value("ops-admin"))
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty())
                .andReturn();

        Cookie sessionCookie = sessionCookie(login.getResponse().getHeader("Set-Cookie"));

        sessionMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("ops-admin"))
                .andExpect(jsonPath("$.data.csrfToken").isNotEmpty());

        sessionMvc.perform(post("/api/v1/auth/logout").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("ops-admin"));

        assertThat(fixture.sessionStore().get(sessionCookie.getValue()).revoked()).isTrue();
    }

    @Test
    void sessionLoginRejectsInvalidPassword() throws Exception {
        sessionFixture().mockMvc().perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userId": "ops-admin",
                                  "password": "wrong"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private SessionFixture sessionFixture() {
        OperatorAuthProperties properties = OperatorAuthProperties.sessionForTests();
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        credentialStore.upsert(new OperatorCredentialRecord(
                "ops-admin",
                encoder.encode("secret"),
                null,
                OperatorCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        ));
        InMemoryUserRolePermissionStore userStore = InMemoryUserRolePermissionStore.bootstrapDefaults();
        InMemoryOperatorSessionStore sessionStore = new InMemoryOperatorSessionStore();
        OperatorSessionService sessionService = new OperatorSessionService(
                sessionStore,
                properties,
                "8h",
                "false"
        );
        ApiAuthService authService = ApiAuthTestSupport.sessionOperatorAuthService(sessionService);
        OperatorCredentialVerifier verifier = new OperatorCredentialVerifier(
                credentialStore,
                userStore,
                encoder
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authService, properties, verifier, sessionService)
        ).build();
        return new SessionFixture(mvc, sessionStore);
    }

    private Cookie sessionCookie(String setCookie) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        return new Cookie(OperatorSessionService.COOKIE_NAME, value);
    }

    private record SessionFixture(MockMvc mockMvc, InMemoryOperatorSessionStore sessionStore) {
    }
}
