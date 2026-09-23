package com.xa.mass.integration.workercorrectness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CorrectnessOptionsTest {

    @Test
    void parsesTheOnePhaseProofInputs() {
        CorrectnessOptions options = CorrectnessOptions.parse(new String[]{
                "--phase=initial",
                "--proof-id=proof",
                "--evidence-file=build/evidence.json",
                "--correctness-spec=correctness-spec.json"
        });

        assertEquals(CorrectnessOptions.Phase.INITIAL, options.requiredPhase());
        assertEquals(
                Path.of("correctness-spec.json").toAbsolutePath().normalize(),
                options.correctnessSpec()
        );
    }

    @Test
    void rejectsTheRetiredFleetArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                CorrectnessOptions.parse(new String[]{
                        "--phase=initial",
                        "--proof-id=proof",
                        "--evidence-file=build/evidence.json",
                        "--fleet-spec=retired.json"
                }));
    }
}
