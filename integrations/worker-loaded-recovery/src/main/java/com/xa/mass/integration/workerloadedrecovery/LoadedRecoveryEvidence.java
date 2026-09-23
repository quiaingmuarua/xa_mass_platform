package com.xa.mass.integration.workerloadedrecovery;

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
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LoadedRecoveryEvidence {

    private LoadedRecoveryEvidence() {
    }

    static String identityDigest(Collection<String> workerIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> sortedWorkerIds = new ArrayList<>(workerIds);
            Collections.sort(sortedWorkerIds);
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
            Map<String, String> workerIdsByLabWorkerKey
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workerGroupId", workerGroupId);
        value.put(
                "workerIdsByLabWorkerKey",
                new LinkedHashMap<>(workerIdsByLabWorkerKey)
        );
        writeJson(path, value);
    }

    static Map<String, String> readBaseline(
            Path path,
            String workerGroupId
    ) {
        Map<String, Object> value = readJson(path, "identity baseline");
        if (!value.keySet().equals(Set.of(
                "workerGroupId",
                "workerIdsByLabWorkerKey"
        ))) {
            throw LoadedRecoveryJson.invalid("Identity baseline fields changed");
        }
        if (!workerGroupId.equals(LoadedRecoveryJson.string(value, "workerGroupId"))) {
            throw LoadedRecoveryJson.invalid("Identity baseline WorkerGroup changed");
        }
        Map<String, Object> raw = LoadedRecoveryJson.object(
                value.get("workerIdsByLabWorkerKey"),
                "workerIdsByLabWorkerKey"
        );
        Map<String, String> workerIds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey().isBlank()
                    || !(entry.getValue() instanceof String workerId)
                    || workerId.isBlank()) {
                throw LoadedRecoveryJson.invalid(
                        "Baseline coordinates and workerIds must be non-blank"
                );
            }
            workerIds.put(entry.getKey(), workerId);
        }
        if (new LinkedHashSet<>(workerIds.values()).size() != workerIds.size()) {
            throw LoadedRecoveryJson.invalid("Identity baseline contains duplicate workerIds");
        }
        return Collections.unmodifiableMap(workerIds);
    }

    static WorkerTopology readTopology(Path path, String workerGroupId) {
        Map<String, Object> value = readJson(path, "private topology");
        if (!value.keySet().equals(Set.of(
                "workerGroupId",
                "retainedLabWorkerKeys",
                "stoppedLabWorkerKeys"
        ))) {
            throw LoadedRecoveryJson.invalid("Private topology fields changed");
        }
        if (!workerGroupId.equals(LoadedRecoveryJson.string(value, "workerGroupId"))) {
            throw LoadedRecoveryJson.invalid("Private topology WorkerGroup changed");
        }
        List<String> retained = stringList(
                value.get("retainedLabWorkerKeys"),
                "retainedLabWorkerKeys"
        );
        List<String> stopped = stringList(
                value.get("stoppedLabWorkerKeys"),
                "stoppedLabWorkerKeys"
        );
        Set<String> all = new LinkedHashSet<>(retained);
        if (all.size() != retained.size() || !all.addAll(stopped)) {
            throw LoadedRecoveryJson.invalid(
                    "Private topology coordinates must be unique"
            );
        }
        if (all.size() != retained.size() + stopped.size()) {
            throw LoadedRecoveryJson.invalid(
                    "Private topology coordinates must be unique"
            );
        }
        return new WorkerTopology(retained, stopped);
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
            throw new IllegalStateException("Could not append loaded recovery timeline", error);
        }
    }

    static void writeSummary(Path path, Map<String, Object> value) {
        writeJson(path, new LinkedHashMap<>(value));
    }

    static void writeExclusive(Path path, Map<String, Object> value) {
        writeJson(path, new LinkedHashMap<>(value), false);
    }

    static Map<String, Object> readObject(Path path, String owner) {
        return readJson(path, owner);
    }

    private static Map<String, Object> readJson(Path path, String owner) {
        try {
            return Jsons.parseObject(Files.readString(
                    path,
                    StandardCharsets.UTF_8
            ));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + owner, error);
        }
    }

    private static List<String> stringList(Object raw, String owner) {
        List<String> result = new ArrayList<>();
        for (Object value : LoadedRecoveryJson.array(raw, owner)) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw LoadedRecoveryJson.invalid(owner + " must contain non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static void writeJson(Path path, Object value) {
        writeJson(path, value, true);
    }

    private static void writeJson(
            Path path,
            Object value,
            boolean replaceExisting
    ) {
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
            if (replaceExisting) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE
                );
            }
            temporary = null;
        } catch (IOException error) {
            throw new IllegalStateException("Could not write loaded recovery evidence", error);
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

    record WorkerTopology(
            List<String> retainedLabWorkerKeys,
            List<String> stoppedLabWorkerKeys
    ) {
    }
}
