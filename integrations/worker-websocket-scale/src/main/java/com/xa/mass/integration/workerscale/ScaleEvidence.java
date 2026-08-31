package com.xa.mass.integration.workerscale;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScaleEvidence {

    private ScaleEvidence() {
    }

    static String identityDigest(List<String> sortedWorkerIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String workerId : sortedWorkerIds) {
                digest.update(workerId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static void writeBaseline(
            Path path,
            String workerGroupId,
            List<String> sortedWorkerIds
    ) {
        writeJson(path, Map.of(
                "workerGroupId", workerGroupId,
                "workerIds", sortedWorkerIds
        ));
    }

    static List<String> readBaseline(Path path, String workerGroupId) {
        Map<String, Object> value;
        try {
            value = Jsons.parseObject(Files.readString(
                    path,
                    StandardCharsets.UTF_8
            ));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read identity baseline", error);
        }
        if (!workerGroupId.equals(ScaleJson.string(value, "workerGroupId"))) {
            throw ScaleJson.invalid("Identity baseline WorkerGroup changed");
        }
        List<String> workerIds = new ArrayList<>();
        for (Object raw : ScaleJson.array(value.get("workerIds"), "workerIds")) {
            if (!(raw instanceof String workerId) || workerId.isBlank()) {
                throw ScaleJson.invalid("Baseline workerId must be non-blank");
            }
            workerIds.add(workerId);
        }
        return List.copyOf(workerIds);
    }

    static void appendTimeline(Path path, Map<String, Object> value) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    Jsons.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException error) {
            throw new IllegalStateException("Could not append scale timeline", error);
        }
    }

    static void writeSummary(Path path, Map<String, Object> value) {
        writeJson(path, new LinkedHashMap<>(value));
    }

    private static void writeJson(Path path, Object value) {
        Path temporary = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporary = Files.createTempFile(
                    parent,
                    "." + path.getFileName() + ".",
                    ".tmp"
            );
            Files.writeString(
                    temporary,
                    Jsons.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            temporary = null;
        } catch (IOException error) {
            throw new IllegalStateException("Could not write scale evidence", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary evidence failure remains authoritative.
                }
            }
        }
    }
}
