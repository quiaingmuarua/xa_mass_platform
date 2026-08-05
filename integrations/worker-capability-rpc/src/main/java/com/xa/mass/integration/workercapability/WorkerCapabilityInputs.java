package com.xa.mass.integration.workercapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

final class WorkerCapabilityInputs {

    private WorkerCapabilityInputs() {
    }

    static List<String> readDistinct(
            Path path,
            String label,
            int minimumCount
    ) throws IOException {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            String value = line.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.size() < minimumCount) {
            throw new IllegalArgumentException(
                    label
                            + " must contain at least "
                            + minimumCount
                            + " distinct values"
            );
        }
        if (values.size() > 1_000) {
            throw new IllegalArgumentException(
                    label + " accepts at most 1000 values"
            );
        }
        return List.copyOf(values);
    }
}
