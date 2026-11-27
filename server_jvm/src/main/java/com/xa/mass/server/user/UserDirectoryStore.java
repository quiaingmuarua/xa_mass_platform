package com.xa.mass.server.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Package-local persistence boundary for {@link UserDirectory}.
 *
 * <p>{@link UserDirectory} owns domain validation, authorization semantics and its immutable in-memory
 * snapshot; concrete stores own startup loading/bootstrapping, durable mutations and resource cleanup.
 */
interface UserDirectoryStore extends AutoCloseable {

    /** Loads the initial directory state (bootstrapping if applicable) and validates it before committing. */
    UserDirectory.Seed load(UnaryOperator<UserDirectory.Seed> validateSeed) throws IOException;

    void createUser(UserDirectory.User user, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
            throws IOException;

    void updateUser(UserDirectory.User user, UserDirectory.Seed nextSeed) throws IOException;

    void updateAccessKeyHash(String userId, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
            throws IOException;

    @Override
    default void close() throws IOException {}

    @FunctionalInterface
    interface SeedSource {
        byte[] read() throws IOException;
    }

    /** Signals a conflict rejection (read-only store or external modification) rather than transient I/O failure. */
    final class ConflictException extends IOException {
        ConflictException(String message) {
            super(message);
        }
    }

    static UserDirectory.Seed parseSeed(ObjectMapper mapper, byte[] document) {
        try {
            return mapper.readerFor(UserDirectory.Seed.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(document);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid user directory document", invalid);
        }
    }

    static byte[] renderSeed(ObjectMapper mapper, UserDirectory.Seed seed) {
        return (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(seed) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static UserDirectoryStore readOnly(ObjectMapper mapper, Resource resource) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(resource, "resource");
        return new ReadOnlyResourceStore(mapper, resource);
    }

    final class ReadOnlyResourceStore implements UserDirectoryStore {
        private final ObjectMapper mapper;
        private final Resource resource;

        private ReadOnlyResourceStore(ObjectMapper mapper, Resource resource) {
            this.mapper = mapper;
            this.resource = resource;
        }

        @Override
        public UserDirectory.Seed load(UnaryOperator<UserDirectory.Seed> validateSeed) throws IOException {
            try (var stream = resource.getInputStream()) {
                return validateSeed.apply(parseSeed(mapper, stream.readAllBytes()));
            }
        }

        @Override
        public void createUser(UserDirectory.User user, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
                throws IOException {
            throw readOnlyConflict();
        }

        @Override
        public void updateUser(UserDirectory.User user, UserDirectory.Seed nextSeed) throws IOException {
            throw readOnlyConflict();
        }

        @Override
        public void updateAccessKeyHash(String userId, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
                throws IOException {
            throw readOnlyConflict();
        }

        private static ConflictException readOnlyConflict() {
            return new ConflictException("User directory seed is read-only");
        }
    }
}
