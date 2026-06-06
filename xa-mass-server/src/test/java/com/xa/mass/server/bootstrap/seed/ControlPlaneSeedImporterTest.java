package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyCredentialStore;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.operator.InMemoryOperatorCredentialStore;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.auth.InMemoryCredentialPrincipalStore;
import com.xa.mass.storage.memory.InMemoryCatalogMetadataStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneSeedImporterTest {

    @Test
    void importRejectsProjectUnknownEventReferenceBeforeWritingCatalogMetadata() throws Exception {
        InMemoryCatalogMetadataStore catalogStore = new InMemoryCatalogMetadataStore();
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        var seed = Files.createTempFile("xa-mass-invalid-catalog", ".json");
        Files.writeString(seed, """
                {
                  "projects": [
                    {
                      "code": "invalidApp",
                      "name": "Invalid App",
                      "eventCodes": ["missing.event"]
                    }
                  ]
                }
                """);

        ControlPlaneSeedImporter importer = new ControlPlaneSeedImporter(
                MassSdk.builder().build(),
                catalogStore,
                apiKeyCredentialService(),
                credentialStore,
                new ObjectMapper(),
                new DefaultResourceLoader()
        );

        assertThatThrownBy(() -> importer.importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                seed.toUri().toString(),
                null,
                null,
                "apply"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown event code missing.event");
        assertThat(catalogStore.getProject("invalidApp")).isEmpty();
    }

    @Test
    void importAppliesOperatorCredentialsFromPasswordHashSeed() throws Exception {
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        var seed = Files.createTempFile("xa-mass-operator-credentials", ".json");
        Files.writeString(seed, """
                {
                  "operatorCredentials": [
                    {
                      "userId": "ops-admin",
                      "passwordHash": "{bcrypt}$2a$10$abcdefghijklmnopqrstuu1fH1s6xu/7a1a48h8bVsjJSl4g0PxpG",
                      "status": "ACTIVE"
                    }
                  ]
                }
                """);

        ControlPlaneSeedImporter importer = importer(credentialStore);

        ControlPlaneSeedImporter.SeedImportResult result = importer.importSeed(
                new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                        null,
                        null,
                        seed.toUri().toString(),
                        "apply"
                ));

        assertThat(result.operatorCredentials()).isEqualTo(1);
        assertThat(credentialStore.get("ops-admin")).isNotNull();
        assertThat(credentialStore.get("ops-admin").passwordHash()).startsWith("{bcrypt}");
    }

    @Test
    void importAppliesApiKeySeedsThroughCredentialLifecycleService() throws Exception {
        InMemoryApiKeyCredentialStore apiKeyStore = new InMemoryApiKeyCredentialStore();
        InMemoryCredentialPrincipalStore principalStore = new InMemoryCredentialPrincipalStore();
        ApiKeyCredentialService apiKeyService = apiKeyCredentialService(apiKeyStore, principalStore);
        var seed = Files.createTempFile("xa-mass-api-key-seed", ".json");
        Files.writeString(seed, """
                {
                  "apiKeys": [
                    {
                      "principalId": "crawler-task-api-key",
                      "rawSecret": "crawler-task-secret",
                      "createdForUserId": "ops-admin",
                      "permissions": ["task:create", "task:edit", "task:view"],
                      "projectScopes": ["crawlerApp"],
                      "eventScopes": ["crawler.fetch-page"],
                      "attributes": {
                        "label": "Crawler Task API Key"
                      }
                    }
                  ]
                }
                """);

        ControlPlaneSeedImporter importer = new ControlPlaneSeedImporter(
                MassSdk.builder().credentialPrincipalStore(principalStore).build(),
                new InMemoryCatalogMetadataStore(),
                apiKeyService,
                new InMemoryOperatorCredentialStore(),
                new ObjectMapper(),
                new DefaultResourceLoader()
        );

        ControlPlaneSeedImporter.SeedImportResult result = importer.importSeed(
                new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                        seed.toUri().toString(),
                        null,
                        null,
                        "apply"
                ));
        importer.importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                seed.toUri().toString(),
                null,
                null,
                "apply"
        ));

        assertThat(result.apiKeys()).isEqualTo(1);
        assertThat(apiKeyStore.list()).hasSize(1);
        assertThat(apiKeyStore.getByPrincipalId("crawler-task-api-key")).isNotNull();
        assertThat(principalStore.authenticate("crawler-task-secret").getPrincipalId())
                .isEqualTo("crawler-task-api-key");
    }

    @Test
    void validateOnlyOperatorCredentialsDoesNotWriteStore() throws Exception {
        InMemoryOperatorCredentialStore credentialStore = new InMemoryOperatorCredentialStore();
        var seed = Files.createTempFile("xa-mass-operator-credentials-validate", ".json");
        Files.writeString(seed, """
                {
                  "operatorCredentials": [
                    {
                      "userId": "ops-admin",
                      "passwordHash": "{bcrypt}$2a$10$abcdefghijklmnopqrstuu1fH1s6xu/7a1a48h8bVsjJSl4g0PxpG"
                    }
                  ]
                }
                """);

        importer(credentialStore).importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                null,
                null,
                seed.toUri().toString(),
                "validate"
        ));

        assertThat(credentialStore.get("ops-admin")).isNull();
    }

    @Test
    void importRejectsPlaintextOperatorPasswordField() throws Exception {
        var seed = Files.createTempFile("xa-mass-operator-plaintext", ".json");
        Files.writeString(seed, """
                {
                  "operatorCredentials": [
                    {
                      "userId": "ops-admin",
                      "password": "secret"
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> importer(new InMemoryOperatorCredentialStore())
                .importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                        null,
                        null,
                        seed.toUri().toString(),
                        "apply"
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed to read control-plane seed resource");
    }

    private ControlPlaneSeedImporter importer(InMemoryOperatorCredentialStore credentialStore) {
        return new ControlPlaneSeedImporter(
                MassSdk.builder().build(),
                new InMemoryCatalogMetadataStore(),
                apiKeyCredentialService(),
                credentialStore,
                new ObjectMapper(),
                new DefaultResourceLoader()
        );
    }

    private ApiKeyCredentialService apiKeyCredentialService() {
        return apiKeyCredentialService(new InMemoryApiKeyCredentialStore(), new InMemoryCredentialPrincipalStore());
    }

    private ApiKeyCredentialService apiKeyCredentialService(InMemoryApiKeyCredentialStore credentialStore,
                                                            InMemoryCredentialPrincipalStore principalStore) {
        return new ApiKeyCredentialService(
                new InMemoryApiKeyApplicationStore(),
                credentialStore,
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                principalStore
        );
    }
}
