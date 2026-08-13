package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityInputsTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void returnsExactlyTheRequiredDistinctInputsInFileOrder()
            throws Exception {
        Path seed = temporaryDirectory.resolve("seed.txt");
        Files.writeString(seed, "one\ntwo\none\nthree\nfour\n");

        assertEquals(
                List.of("one", "two", "three"),
                WorkerCapabilityInputs.readDistinct(seed, "seed", 3)
        );
    }

    @Test
    void rejectsAnInsufficientDistinctInputSet() throws Exception {
        Path seed = temporaryDirectory.resolve("seed.txt");
        Files.writeString(seed, "one\none\ntwo\n");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> WorkerCapabilityInputs.readDistinct(
                        seed,
                        "seed",
                        3
                )
        );
        assertTrue(error.getMessage().contains(
                "at least 3 distinct values"
        ));
    }
}
