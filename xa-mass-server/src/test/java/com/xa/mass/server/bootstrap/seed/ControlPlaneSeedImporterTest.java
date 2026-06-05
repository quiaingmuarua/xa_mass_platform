package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.operator.InMemoryOperatorCredentialStore;
import com.xa.mass.sdk.MassSdk;
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
                credentialStore,
                new ObjectMapper(),
                new DefaultResourceLoader()
        );
    }
}
