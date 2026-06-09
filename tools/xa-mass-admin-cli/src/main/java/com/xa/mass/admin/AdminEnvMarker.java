package com.xa.mass.admin;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

record AdminEnvMarker(int version,
                      String baseUrl,
                      String profile,
                      String mode,
                      String catalogManifestSha256,
                      String rulesManifestSha256,
                      String workerSpecSha256,
                      String taskCredentialSha256,
                      String workerCredentialPolicySha256,
                      List<String> requiredProjects,
                      List<String> requiredEvents,
                      Instant initializedAt,
                      Instant verifiedAt) {
    static final int VERSION = 1;

    boolean matches(AdminEnvMarker expected) {
        return expected != null
                && version == expected.version
                && Objects.equals(baseUrl, expected.baseUrl)
                && Objects.equals(profile, expected.profile)
                && Objects.equals(mode, expected.mode)
                && Objects.equals(catalogManifestSha256, expected.catalogManifestSha256)
                && Objects.equals(rulesManifestSha256, expected.rulesManifestSha256)
                && Objects.equals(workerSpecSha256, expected.workerSpecSha256)
                && Objects.equals(taskCredentialSha256, expected.taskCredentialSha256)
                && Objects.equals(workerCredentialPolicySha256, expected.workerCredentialPolicySha256)
                && Objects.equals(requiredProjects, expected.requiredProjects)
                && Objects.equals(requiredEvents, expected.requiredEvents);
    }

    static AdminEnvMarker expected(AdminEnvConfig.Loaded loaded,
                                   Path catalogManifest,
                                   Path rulesManifest,
                                   Path workerSpec,
                                   DesiredApiKey taskCredential,
                                   Instant now,
                                   ObjectMapper objectMapper) {
        try {
            return new AdminEnvMarker(
                    VERSION,
                    loaded.config().server().baseUrl(),
                    loaded.config().server().profile(),
                    loaded.config().environment().mode().name(),
                    sha256(Files.readAllBytes(catalogManifest)),
                    sha256(Files.readAllBytes(rulesManifest)),
                    sha256(Files.readAllBytes(workerSpec)),
                    sha256(objectMapper.writeValueAsBytes(taskCredential.desiredStateBody())),
                    sha256(objectMapper.writeValueAsBytes(loaded.config().credentials().workerCredentials())),
                    loaded.config().verify().requiredProjects(),
                    loaded.config().verify().requiredEvents(),
                    now,
                    now
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to build env marker fingerprint", e);
        }
    }

    AdminEnvMarker verifiedAt(Instant now) {
        return new AdminEnvMarker(
                version,
                baseUrl,
                profile,
                mode,
                catalogManifestSha256,
                rulesManifestSha256,
                workerSpecSha256,
                taskCredentialSha256,
                workerCredentialPolicySha256,
                requiredProjects,
                requiredEvents,
                initializedAt == null ? now : initializedAt,
                now
        );
    }

    static AdminEnvMarker read(Path file, ObjectMapper objectMapper) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), AdminEnvMarker.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read env marker: " + file, e);
        }
    }

    static void write(Path file, AdminEnvMarker marker, ObjectMapper objectMapper) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(marker)
                    + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to write env marker: " + file, e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
