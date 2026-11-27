package com.xa.mass.server.user;

import static com.xa.mass.server.testing.TaskCallers.devKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.AccessKeyFilter;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.TaskCallerHttpConfiguration;
import com.xa.mass.server.api.v1.controller.UserController;
import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class SqliteUserDirectoryTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @TempDir Path temp;

    private static ProjectDirectory projects() {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("messages", List.of("g"), null),
                new ProjectDefinition("app-checks", List.of("g"), null))), List.of());
    }

    private static MockMvc client(UserDirectory directory) {
        return MockMvcBuilders.standaloneSetup(new UserController(directory))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver())
                .addFilters(new AccessKeyFilter(directory, MAPPER))
                .build();
    }

    private static String bearer(String userId) {
        return "Bearer " + devKey(userId);
    }

    private static ServerErrorCode code(Throwable error) {
        return ((ServerException) error).errorCode();
    }

    @Test
    void bootstrapsOnceFromUsersFileWithoutLockingAndBootsAfterSeedRemoval() throws Exception {
        Path seedFile = temp.resolve("users.json");
        byte[] devBytes = new ClassPathResource("users/development-directory.json").getInputStream().readAllBytes();
        Files.write(seedFile, devBytes);
        Path dbPath = temp.resolve("control-plane.sqlite");
        var loader = new DefaultResourceLoader();

        try (var db = ControlPlaneDatabase.open(dbPath);
             var directory = new UserDirectory(MAPPER, loader, seedFile.toUri().toString(), true, projects(), db)) {
            assertThat(Files.exists(temp.resolve("users.json.lock"))).isFalse();
            assertThat(Files.readAllBytes(seedFile)).isEqualTo(devBytes);
            assertThat(directory.users()).hasSize(3);
            assertThat(directory.authenticate(devKey("1000")).userId()).isEqualTo("1000");

            var admin = directory.authenticate(devKey("1000"));
            directory.create(admin, new UserDirectory.User("userc", "User C", "operator", List.of("messages")));
            // SQLite mode never mutates the original users.json seed file.
            assertThat(Files.readAllBytes(seedFile)).isEqualTo(devBytes);
        }

        // Remove the original seed file; subsequent consumer restart loads directly from SQLite even with blank seed.
        Files.delete(seedFile);
        try (var db = ControlPlaneDatabase.open(dbPath);
             var restarted = new UserDirectory(MAPPER, loader, "", true, projects(), db)) {
            assertThat(restarted.users()).extracting(UserDirectory.UserView::userId)
                    .containsExactly("1000", "1001", "1002", "userc");
            assertThat(restarted.authenticate(devKey("1001")).userId()).isEqualTo("1001");
        }
    }

    @Test
    void emptyDatabaseStillRequiresConfiguredSeedOnConsumerProfile() throws Exception {
        Path dbPath = temp.resolve("empty-consumer.sqlite");
        var loader = new DefaultResourceLoader();
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            assertThatThrownBy(() -> new UserDirectory(MAPPER, loader, " ", true, projects(), db))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("xa.mass.users.seed");
        }
        // When requireConfiguredSeed=false, blank seed falls back to development seed and populates SQLite.
        try (var db = ControlPlaneDatabase.open(dbPath);
             var dev = new UserDirectory(MAPPER, loader, "", false, projects(), db)) {
            assertThat(dev.users()).hasSize(3);
            assertThat(dev.authenticate(devKey("1000")).userId()).isEqualTo("1000");
        }
    }

    @Test
    void httpCrudAndAccessKeyRotationPersistAcrossRestartWithoutStoringPlaintextKeys() throws Exception {
        Path dbPath = temp.resolve("crud.sqlite");
        String issuedKey;
        try (var db = ControlPlaneDatabase.open(dbPath);
             var directory = new UserDirectory(MAPPER, db,
                     new ClassPathResource("users/development-directory.json"), false, projects())) {
            var mvc = client(directory);
            mvc.perform(post("/api/v1/users").header("Authorization", bearer("1000"))
                            .contentType(MediaType.APPLICATION_JSON).content("""
                            {"userId":"userc","displayName":"User C","role":"operator","projectIds":["messages"]}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("userc"))
                    .andExpect(jsonPath("$.hasAccessKey").value(false));

            mvc.perform(put("/api/v1/users/userc").header("Authorization", bearer("1000"))
                            .contentType(MediaType.APPLICATION_JSON).content("""
                            {"displayName":"User C updated","role":"operator","projectIds":["app-checks","messages"]}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("User C updated"))
                    .andExpect(jsonPath("$.projectIds[0]").value("app-checks"))
                    .andExpect(jsonPath("$.projectIds[1]").value("messages"));

            String body = mvc.perform(post("/api/v1/users/userc/access-key").header("Authorization", bearer("1000")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.userId").value("userc"))
                    .andReturn().getResponse().getContentAsString();
            issuedKey = MAPPER.readTree(body).get("accessKey").asString();
            assertThat(issuedKey).matches("xak_[A-Za-z0-9_-]{43}");

            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + issuedKey))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("userc"))
                    .andExpect(jsonPath("$.displayName").value("User C updated"));

            // Forbidden for operator and rejected for self-edit.
            mvc.perform(post("/api/v1/users/1001/access-key").header("Authorization", "Bearer " + issuedKey))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/users/1000/access-key").header("Authorization", bearer("1000")))
                    .andExpect(status().isBadRequest());

            // Verify raw SQLite contains only the SHA-256 hash, never the plaintext key.
            String storedHash = db.inTransaction(connection -> {
                try (var stmt = connection.prepareStatement("SELECT access_key_sha256 FROM cp_users WHERE user_id = ?")) {
                    stmt.setString(1, "userc");
                    try (var rows = stmt.executeQuery()) {
                        rows.next();
                        return rows.getString(1);
                    }
                }
            });
            assertThat(storedHash).isEqualTo(UserDirectory.hash(issuedKey)).isNotEqualTo(issuedKey);
        }

        try (var db = ControlPlaneDatabase.open(dbPath);
             var restarted = new UserDirectory(MAPPER, db, null, true, projects())) {
            assertThat(restarted.authenticate(issuedKey).userId()).isEqualTo("userc");
            assertThat(restarted.users()).filteredOn(u -> u.userId().equals("userc")).singleElement()
                    .satisfies(user -> {
                        assertThat(user.displayName()).isEqualTo("User C updated");
                        assertThat(user.projectIds()).containsExactly("app-checks", "messages");
                        assertThat(user.hasAccessKey()).isTrue();
                    });
            var cleared = restarted.clearAccessKey(restarted.authenticate(devKey("1000")), "userc");
            assertThat(cleared.hasAccessKey()).isFalse();
            assertThatThrownBy(() -> restarted.authenticate(issuedKey))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.UNAUTHENTICATED));
        }
    }

    @Test
    void hotPathReadsUseInMemorySnapshotWithoutSqliteIoAndFailedWritesKeepSnapshotIntact() throws Exception {
        Path dbPath = temp.resolve("snapshot-reads.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath);
             var directory = new UserDirectory(MAPPER, db,
                     new ClassPathResource("users/development-directory.json"), false, projects())) {
            // Close the underlying SQLite connection while UserDirectory remains active.
            db.close();
            // Hot-path reads and authentication still succeed with zero SQLite I/O.
            assertThat(directory.authenticate(devKey("1000")).userId()).isEqualTo("1000");
            assertThat(directory.containsUser("1001")).isTrue();
            assertThat(directory.users()).hasSize(3);
            assertThat(directory.roles()).hasSize(2);
            // Writes fail explicitly with 503/USER_DIRECTORY_UNAVAILABLE without mutating the snapshot.
            assertThatThrownBy(() -> directory.clearAccessKey(directory.authenticate(devKey("1000")), "1001"))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.USER_DIRECTORY_UNAVAILABLE));
            assertThat(directory.authenticate(devKey("1001")).userId()).isEqualTo("1001");
        }
    }

    @Test
    void injectedSqliteFailureLeavesDatabaseAndInMemorySnapshotUnchanged() throws Exception {
        Path dbPath = temp.resolve("fault.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath)) {
            var failingStore = new SqliteUserDirectoryStore(db, MAPPER,
                    () -> UserDirectory.readResourceSeed(new ClassPathResource("users/development-directory.json"), false)) {
                @Override
                void updateUser(UserDirectory.User user) throws IOException {
                    throw new IOException("injected sqlite write failure");
                }
            };
            try (var directory = new UserDirectory(projects(), failingStore)) {
                var admin = directory.authenticate(devKey("1000"));
                assertThatThrownBy(() -> directory.update(admin, "1001",
                        new UserDirectory.UpdateUser("Changed", "operator", List.of("messages"))))
                        .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.USER_DIRECTORY_UNAVAILABLE));
                assertThat(directory.users()).filteredOn(u -> u.userId().equals("1001")).singleElement()
                        .extracting(UserDirectory.UserView::displayName).isNotEqualTo("Changed");
            }
        }
    }

    @Test
    void concurrentDuplicateCreatesHaveOneWinnerAndMalformedSeedLeavesDatabaseEmpty() throws Exception {
        Path badDbPath = temp.resolve("malformed-seed.sqlite");
        String invalidSeed = new String(
                new ClassPathResource("users/development-directory.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).replace(UserDirectory.hash(devKey("1000")), "not-a-sha256");
        Path badSeedFile = Files.writeString(temp.resolve("bad-users.json"), invalidSeed, StandardCharsets.UTF_8);
        try (var db = ControlPlaneDatabase.open(badDbPath)) {
            assertThatThrownBy(() -> new UserDirectory(MAPPER, new DefaultResourceLoader(),
                    badSeedFile.toUri().toString(), true, projects(), db))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new SqliteUserDirectoryStore(db, MAPPER).isEmpty()).isTrue();
        }

        Path dbPath = temp.resolve("concurrent.sqlite");
        try (var db = ControlPlaneDatabase.open(dbPath);
             var directory = new UserDirectory(MAPPER, db,
                     new ClassPathResource("users/development-directory.json"), false, projects());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var admin = directory.authenticate(devKey("1000"));
            var results = executor.invokeAll(IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Boolean>) () -> {
                        try {
                            directory.create(admin, new UserDirectory.User("userc", "C", "operator", List.of("messages")));
                            return true;
                        } catch (ServerException rejected) {
                            return false;
                        }
                    }).toList());
            int winners = 0;
            for (var result : results) {
                if (result.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
            assertThat(directory.users()).hasSize(4);
        }
    }
}
