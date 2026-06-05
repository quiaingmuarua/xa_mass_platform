package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                new ObjectMapper(),
                new DefaultResourceLoader()
        );

        assertThatThrownBy(() -> importer.importSeed(new ControlPlaneSeedImporter.ControlPlaneSeedImportRequest(
                seed.toUri().toString(),
                null,
                "apply"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown event code missing.event");
        assertThat(catalogStore.getProject("invalidApp")).isEmpty();
    }
}
