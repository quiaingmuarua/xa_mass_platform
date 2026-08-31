package com.xa.mass.integration.workerscale;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaleEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void privateIdentityBaselineRoundTripsWithoutEnteringSummaryEvidence() {
        List<String> workerIds = List.of("worker-a", "worker-b");
        Path baseline = temporaryDirectory.resolve("private/worker-ids.json");

        ScaleEvidence.writeBaseline(baseline, "group-a", workerIds);

        assertThat(ScaleEvidence.readBaseline(baseline, "group-a"))
                .containsExactlyElementsOf(workerIds);
        assertThat(ScaleEvidence.identityDigest(workerIds))
                .hasSize(64)
                .isEqualTo(ScaleEvidence.identityDigest(List.copyOf(workerIds)));
    }
}
