package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AndroidWorkerProofOptionsTest {

    @Test
    void parsesFixedProofArguments() {
        AndroidWorkerProofOptions options = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=initial",
                        "--proof-id=proof-1",
                        "--evidence-file=build/evidence.json",
                        "--maximum-wait-millis=5000",
                        "--request-timeout-millis=6000",
                        "--android-api-level=33"
                }
        );

        assertEquals("initial", options.phase());
        assertEquals("proof-1", options.proofId());
        assertEquals(5_000L, options.maximumWait().toMillis());
        assertEquals(6_000L, options.requestTimeout().toMillis());
        assertEquals(33, options.androidApiLevel());
        assertEquals(
                "scenario-websocket",
                options.endpointManagerId()
        );
        assertEquals(
                Path.of("build/evidence.json").toAbsolutePath().normalize(),
                options.evidenceFile()
        );
    }

    @Test
    void rejectsUnknownDuplicateAndInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                AndroidWorkerProofOptions.parse(new String[]{
                        "--phase=initial",
                        "--proof-id=p",
                        "--evidence-file=e.json",
                        "--unknown=value"
                }));
        assertThrows(IllegalArgumentException.class, () ->
                AndroidWorkerProofOptions.parse(new String[]{
                        "--phase=initial",
                        "--phase=active",
                        "--proof-id=p",
                        "--evidence-file=e.json"
                }));
        AndroidWorkerProofOptions invalid = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=initial",
                        "--proof-id=p",
                        "--evidence-file=e.json",
                        "--maximum-wait-millis=0"
                }
        );
        assertThrows(IllegalArgumentException.class, invalid::maximumWait);
    }

    @Test
    void enforcesPhaseBaselineOwnership() {
        AndroidWorkerProofOptions initial = AndroidWorkerProofOptions.parse(
                new String[]{
                        "--phase=initial",
                        "--proof-id=p",
                        "--evidence-file=e.json"
                }
        );
        assertEquals(null, initial.baselineFile(false));
        assertThrows(
                IllegalArgumentException.class,
                () -> initial.baselineFile(true)
        );
    }
}
