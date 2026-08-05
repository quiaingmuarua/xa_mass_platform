package com.xa.mass.integration.workercapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class WorkerCapabilityResultWriter {

    private WorkerCapabilityResultWriter() {
    }

    static void writeAtomically(
            Path outputPath,
            List<String> encodedResults
    ) throws IOException {
        Path temporaryPath = outputPath.resolveSibling(
                outputPath.getFileName() + ".tmp"
        );
        if (Files.exists(outputPath) || Files.exists(temporaryPath)) {
            throw new IllegalStateException(
                    "Result output already exists: " + outputPath
            );
        }
        try {
            Files.write(
                    temporaryPath,
                    encodedResults,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            moveToFinalPath(temporaryPath, outputPath);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporaryPath);
            throw error;
        }
    }

    private static void moveToFinalPath(
            Path temporaryPath,
            Path outputPath
    ) throws IOException {
        try {
            Files.move(
                    temporaryPath,
                    outputPath,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporaryPath, outputPath);
        }
    }
}
