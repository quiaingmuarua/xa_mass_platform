package com.xa.mass.sdk.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySubmitterRegistryTest {

    @Test
    void registerAndAuthenticateRoundTripsSubmitterBinding() {
        InMemorySubmitterRegistry registry = new InMemorySubmitterRegistry();

        registry.register(SubmitterRegistration.builder()
                .principalId("crawler-submitter")
                .credential("crawler-secret")
                .userId("crawler-user")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("lane", "crawler"))
                .build());

        PrincipalContext principal = registry.authenticate("crawler-secret");
        assertThat(principal).isNotNull();
        assertThat(principal.getPrincipalId()).isEqualTo("crawler-submitter");
        assertThat(registry.getSubmitter("crawler-submitter")).isNotNull();
        assertThat(registry.listSubmitters()).hasSize(1);
    }

    @Test
    void projectionWriterPublishesCredentialWithoutResourceFacadeRead() {
        CredentialAuthProjectionWriter projectionWriter = new InMemorySubmitterRegistry();

        projectionWriter.projectCredential(SubmitterRegistration.builder()
                .principalId("api-key-principal")
                .credential("api-secret")
                .userId("ops-user")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("opsApp"))
                .eventScopes(List.of("tool.ops.run"))
                .build());

        assertThat(projectionWriter.hasProjectedCredential("api-key-principal")).isTrue();
    }

    @Test
    void loadDurableRestoresAuthenticationWithoutPlainCredential() {
        InMemorySubmitterRegistry registry = new InMemorySubmitterRegistry();
        SubmitterRegistration registration = SubmitterRegistration.builder()
                .principalId("ops-submitter")
                .credential("ops-secret")
                .userId("ops-user")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("demoApp"))
                .eventScopes(List.of("tool.time.now"))
                .build();

        registry.loadDurable(registration.toProfile(), CredentialHashing.sha256(registration.getCredential()));

        PrincipalContext principal = registry.authenticate("ops-secret");
        assertThat(principal).isNotNull();
        assertThat(principal.getPrincipalId()).isEqualTo("ops-submitter");
    }
}
