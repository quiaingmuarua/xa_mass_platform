package com.xa.mass.sdk.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCredentialPrincipalStoreTest {

    @Test
    void registerAndAuthenticateRoundTripsCredentialPrincipalBinding() {
        InMemoryCredentialPrincipalStore store = new InMemoryCredentialPrincipalStore();

        store.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("crawler-task-key")
                .credential("crawler-secret")
                .userId("crawler-user")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("lane", "crawler"))
                .build());

        PrincipalContext principal = store.authenticate("crawler-secret");
        assertThat(principal).isNotNull();
        assertThat(principal.getPrincipalId()).isEqualTo("crawler-task-key");
        assertThat(store.getCredentialPrincipal("crawler-task-key")).isNotNull();
        assertThat(store.listCredentialPrincipals()).hasSize(1);
    }

    @Test
    void projectionWriterPublishesCredentialWithoutResourceFacadeRead() {
        CredentialAuthProjectionWriter projectionWriter = new InMemoryCredentialPrincipalStore();

        projectionWriter.projectCredential(CredentialPrincipalRegistration.builder()
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
        InMemoryCredentialPrincipalStore store = new InMemoryCredentialPrincipalStore();
        CredentialPrincipalRegistration registration = CredentialPrincipalRegistration.builder()
                .principalId("ops-task-key")
                .credential("ops-secret")
                .userId("ops-user")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("demoApp"))
                .eventScopes(List.of("tool.time.now"))
                .build();

        store.loadDurable(registration.toProfile(), CredentialHashing.sha256(registration.getCredential()));

        PrincipalContext principal = store.authenticate("ops-secret");
        assertThat(principal).isNotNull();
        assertThat(principal.getPrincipalId()).isEqualTo("ops-task-key");
    }
}
