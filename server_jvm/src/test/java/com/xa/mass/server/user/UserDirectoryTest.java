package com.xa.mass.server.user;

import com.xa.mass.server.api.AccessKeyFilter;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.TaskCallerHttpConfiguration;
import com.xa.mass.server.api.v1.controller.UserController;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.testing.TaskCallers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static com.xa.mass.server.testing.TaskCallers.devKey;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserDirectoryTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @TempDir Path temp;

    private static ProjectDirectory projects() {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("messages", List.of("g"), null), new ProjectDefinition("app-checks", List.of("g"), null))), List.of());
    }
    private UserDirectory writable() { return TaskCallers.writableDevelopmentDirectory(temp, projects()); }
    private static UserDirectory builtIn() throws IOException {
        return new UserDirectory(MAPPER, new ClassPathResource("users/development-directory.json"), projects());
    }
    private static MockMvc client(UserDirectory directory) {
        return MockMvcBuilders.standaloneSetup(new UserController(directory)).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(TaskCallerHttpConfiguration.resolver())
                .addFilters(new AccessKeyFilter(directory, MAPPER)).build();
    }
    private static String bearer(String userId) { return "Bearer " + devKey(userId); }
    private static ServerErrorCode code(Throwable error) { return ((ServerException) error).errorCode(); }

    @Test void httpProofSeedAndFocusedFixturesProduceTheSameRoleSnapshot() throws Exception {
        var proof = new UserDirectory(MAPPER, new ClassPathResource("users/proof-directory.json"),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of()));
        assertThat(proof.roles()).isEqualTo(builtIn().roles());
        // Fault-injection matchers compare the admitted value, including its immutable grants.
        assertThat(proof.authenticate(devKey("proof"))).isEqualTo(TaskCallers.caller("proof", "admin"));
    }

    @Test void externalProfilesMustNameTheirSeedAndOnlyFileSeedsAreWritable() throws Exception {
        var loader = new org.springframework.core.io.DefaultResourceLoader();
        assertThatThrownBy(() -> new UserDirectory(MAPPER, loader, " ", true, projects()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("xa.mass.users.seed");
        var builtIn = new UserDirectory(MAPPER, loader, "", false, projects());
        assertThatThrownBy(() -> builtIn.clearAccessKey(builtIn.authenticate(devKey("1000")), "1001"))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.USER_DIRECTORY_CONFLICT));
        TaskCallers.writableDevelopmentDirectory(temp, projects()).close();
        try (var configured = new UserDirectory(MAPPER, loader, temp.resolve("users.json").toUri().toString(), true, projects())) {
            assertThat(configured.clearAccessKey(configured.authenticate(devKey("1000")), "1001").hasAccessKey()).isFalse();
        }
    }

    @Test void writesArePersistedForTheNextServerAndReturnOnlyKeyPresence() throws Exception {
        try (var state = writable()) {
            var client = client(state);
            client.perform(post("/api/v1/users").header("Authorization", bearer("1000"))
                            .contentType(MediaType.APPLICATION_JSON).content("""
                            {"userId":"userc","displayName":"User C","role":"operator","projectIds":["messages"]}
                            """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("userc"))
                    .andExpect(jsonPath("$.hasAccessKey").value(false));
            client.perform(put("/api/v1/users/userc").header("Authorization", bearer("1000"))
                            .contentType(MediaType.APPLICATION_JSON).content("""
                            {"displayName":"User C updated","role":"operator","projectIds":["app-checks"]}
                            """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds[0]").value("app-checks"));
            client.perform(get("/api/v1/users").header("Authorization", bearer("1001")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[3].displayName").value("User C updated"))
                    .andExpect(jsonPath("$[0].hasAccessKey").value(true))
                    .andExpect(jsonPath("$[0].accessKeySha256").doesNotExist());
            client.perform(get("/api/v1/roles").header("Authorization", bearer("1001")))
                    .andExpect(jsonPath("$[1].permissions").value(org.hamcrest.Matchers.hasItem("messages.use")));
        }
        try (var restarted = new UserDirectory(MAPPER, temp.resolve("users.json"), projects())) {
            assertThat(restarted.users()).extracting(UserDirectory.UserView::displayName).contains("User C updated");
        }
    }

    @Test void meReturnsTheDirectoryRecordAndRejectsMissingOrUnknownKeys() throws Exception {
        var client = client(builtIn());
        client.perform(get("/api/v1/me").header("Authorization", bearer("1001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("1001"))
                .andExpect(jsonPath("$.role.id").value("operator"))
                .andExpect(jsonPath("$.role.permissions").value(org.hamcrest.Matchers.hasItem("app-checks.tasks")))
                .andExpect(jsonPath("$.projectIds[0]").value("app-checks"));
        for (var header : List.of("", "Bearer ", "Bearer xak_dev_missing", "Basic eGFrX2Rldl8xMDAw", "Bearer not-a-key"))
            client.perform(header.isEmpty() ? get("/api/v1/me") : get("/api/v1/me").header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value(ServerErrorCode.UNAUTHENTICATED.code()));
        client.perform(get("/api/v1/me").header("Authorization", bearer("1000")).header("Authorization", bearer("1001")))
                .andExpect(status().isUnauthorized());
    }

    @Test void rejectsUnauthorizedInvalidSelfAndReadOnlyChangesWithoutChangingTheDirectory() throws Exception {
        try (var state = writable()) {
            var client = client(state);
            String valid = """
                    {"userId":"userc","displayName":"User C","role":"operator","projectIds":["messages"]}
                    """;
            client.perform(post("/api/v1/users").header("Authorization", bearer("1001"))
                    .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
            client.perform(post("/api/v1/users/1002/access-key").header("Authorization", bearer("1001")))
                    .andExpect(status().isForbidden());
            for (String invalid : List.of(valid.replace("userc", "1001"), valid.replace("operator", "service"),
                    valid.replace("messages", "unknown"), valid.replace("userc", "INVALID")))
                client.perform(post("/api/v1/users").header("Authorization", bearer("1000"))
                        .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
            for (var self : List.of(post("/api/v1/users/1000/access-key"), delete("/api/v1/users/1000/access-key")))
                client.perform(self.header("Authorization", bearer("1000"))).andExpect(status().isBadRequest());
            assertThat(state.users()).hasSize(3);
            var admin = state.authenticate(devKey("1000"));
            var granted = new java.util.ArrayList<>(List.of("messages"));
            var user = state.create(admin, new UserDirectory.User("userc", "C", "operator", granted));
            granted.add("app-checks");
            assertThat(user.projectIds()).containsExactly("messages");
            assertThatThrownBy(() -> user.projectIds().add("sms")).isInstanceOf(UnsupportedOperationException.class);
        }
        var readOnly = builtIn();
        client(readOnly).perform(post("/api/v1/users/1001/access-key").header("Authorization", bearer("1000")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ServerErrorCode.USER_DIRECTORY_CONFLICT.code()));
        assertThat(readOnly.authenticate(devKey("1001")).userId()).isEqualTo("1001");
    }

    @Test void issuedKeysAreShownOnceReplacePreviousKeysAndSurviveRestart() throws Exception {
        String issued;
        try (var state = writable()) {
            var client = client(state);
            var body = client.perform(post("/api/v1/users/1001/access-key").header("Authorization", bearer("1000")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.userId").value("1001")).andReturn().getResponse().getContentAsString();
            issued = MAPPER.readTree(body).get("accessKey").asString();
            assertThat(issued).matches("xak_[A-Za-z0-9_-]{43}");
            assertThatThrownBy(() -> state.authenticate(devKey("1001")))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.UNAUTHENTICATED));
            client.perform(get("/api/v1/me").header("Authorization", "Bearer " + issued))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("1001"));
            assertThat(Files.readString(temp.resolve("users.json"), StandardCharsets.UTF_8))
                    .doesNotContain(issued).contains(UserDirectory.hash(issued));
        }
        try (var restarted = new UserDirectory(MAPPER, temp.resolve("users.json"), projects())) {
            assertThat(restarted.authenticate(issued).userId()).isEqualTo("1001");
            var cleared = restarted.clearAccessKey(restarted.authenticate(devKey("1000")), "1001");
            assertThat(cleared.hasAccessKey()).isFalse();
            assertThatThrownBy(() -> restarted.authenticate(issued))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.UNAUTHENTICATED));
        }
    }

    @Test void roleAndProjectsComeFromTheCurrentRecordWhileAdmittedCallersKeepTheirSnapshot() throws Exception {
        try (var state = writable()) {
            var admin = state.authenticate(devKey("1000"));
            var actor = state.authenticate(devKey("1001"));
            actor.requireProject("messages");
            assertThatThrownBy(() -> actor.requireProject("other")).isInstanceOf(ServerException.class);
            assertThatThrownBy(() -> actor.selectUser("1002")).isInstanceOf(ServerException.class);
            state.update(admin, "1001", new UserDirectory.UpdateUser("A", "operator", List.of("app-checks")));
            actor.requireProject("messages");
            assertThatThrownBy(() -> state.authenticate(devKey("1001")).requireProject("messages")).isInstanceOf(ServerException.class);
            state.update(admin, "1001", new UserDirectory.UpdateUser("A", "admin", List.of()));
            var promoted = state.authenticate(devKey("1001"));
            assertThat(promoted.role()).isEqualTo("admin");
            assertThat(promoted.identityKey()).isEqualTo(actor.identityKey());
            promoted.requirePermission("users.manage");
            state.update(admin, "1001", new UserDirectory.UpdateUser("A", "operator", List.of("messages")));
            promoted.requirePermission("users.manage");
            assertThatThrownBy(() -> state.authenticate(devKey("1001")).requirePermission("users.manage")).isInstanceOf(ServerException.class);
        }
    }

    @Test void duplicateConcurrentCreatesHaveOneWinner() throws Exception {
        try (var state = writable(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var admin = state.authenticate(devKey("1000"));
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(index -> (java.util.concurrent.Callable<Boolean>) () -> {
                try { state.create(admin, new UserDirectory.User("userc", "C", "operator", List.of("messages"))); return true; }
                catch (ServerException conflict) { return false; }
            }).toList());
            int winners = 0;
            for (var result : results) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(state.users()).hasSize(4);
        }
    }

    @Test void admittedBusinessWorkDoesNotHoldTheDirectoryGateOrChangeIdentityMidOperation() throws Exception {
        try (var state = writable(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var admitted = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var work = executor.submit(() -> {
                var caller = state.authenticate(devKey("1001"));
                admitted.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Business gate timed out");
                caller.requireProject("messages");
                return caller.identityKey();
            });
            try {
                assertThat(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> state.update(state.authenticate(devKey("1000")), "1001",
                        new UserDirectory.UpdateUser("A changed", "operator", List.of())))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThatThrownBy(() -> state.authenticate(devKey("1001")).requireProject("messages")).isInstanceOf(ServerException.class);
            } finally { release.countDown(); }
            assertThat(work.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("user:1001");
        }
    }

    @Test void externalEditsWhileRunningAreRejectedAndTheFileIsOwnedByOneDirectory() throws Exception {
        try (var state = writable()) {
            Path file = temp.resolve("users.json");
            assertThatThrownBy(() -> new UserDirectory(MAPPER, file, projects())).isInstanceOf(IOException.class);
            String edited = Files.readString(file, StandardCharsets.UTF_8).replace("用户 B", "Hand edited");
            Files.writeString(file, edited, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> state.issueAccessKey(state.authenticate(devKey("1000")), "1002"))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.USER_DIRECTORY_CONFLICT));
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(edited);
            assertThat(state.authenticate(devKey("1002")).userId()).isEqualTo("1002");
        }
        try (var reopened = new UserDirectory(MAPPER, temp.resolve("users.json"), projects())) {
            assertThat(reopened.users()).extracting(UserDirectory.UserView::displayName).contains("Hand edited");
        }
    }

    @Test void failedPublicationLeavesTheFileAndSnapshotUnchanged() throws Exception {
        Path file = temp.resolve("users.json");
        TaskCallers.writableDevelopmentDirectory(temp, projects()).close();
        String original = Files.readString(file, StandardCharsets.UTF_8);
        var failing = new UserDirectory.FilePublication() {
            @Override void replace(Path pending, Path target) throws IOException { throw new IOException("injected replace failure"); }
        };
        try (var state = new UserDirectory(MAPPER, file, projects(), failing)) {
            assertThatThrownBy(() -> state.clearAccessKey(state.authenticate(devKey("1000")), "1001"))
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ServerErrorCode.USER_DIRECTORY_UNAVAILABLE));
            assertThat(state.authenticate(devKey("1001")).userId()).isEqualTo("1001");
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
            try (var leftovers = Files.list(temp)) {
                assertThat(leftovers.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("users.json", "users.json.lock");
            }
        }
    }

    @Test void loadRejectsMalformedDuplicateAndUnknownKeyFields() throws Exception {
        String base = new String(new ClassPathResource("users/development-directory.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String devAdminHash = UserDirectory.hash(devKey("1000"));
        for (String invalid : List.of(
                base.replace(devAdminHash, "ABC"),
                base.replace(UserDirectory.hash(devKey("1001")), devAdminHash),
                base.replace("\"accessKeySha256\"", "\"accessKey\""))) {
            Path file = Files.writeString(temp.resolve("invalid-" + Integer.toHexString(invalid.hashCode()) + ".json"), invalid);
            assertThatThrownBy(() -> new UserDirectory(MAPPER, file, projects())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void accessUsesTheSeedRolePolicyRatherThanRoleNameBranches() throws Exception {
        var roles = builtIn().roles().stream().map(role -> role.id().equals("operator")
                ? new UserDirectory.Role(role.id(), role.label(), List.of("runtime.view"), "all", "assigned", role.home())
                : new UserDirectory.Role(role.id(), role.label(), List.of("users.manage"), role.taskScope(), role.projectScope(), role.home())).toList();
        var users = builtIn().users().stream().map(user -> new UserDirectory.SeedUser(user.userId(), user.displayName(), user.role(),
                user.projectIds(), UserDirectory.hash(devKey(user.userId())))).toList();
        var state = new UserDirectory(MAPPER, new org.springframework.core.io.ByteArrayResource(
                MAPPER.writeValueAsBytes(new UserDirectory.Seed(roles, users))),
                new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of()));
        var operator = state.authenticate(devKey("1001"));
        operator.requirePermission("runtime.view");
        assertThat(operator.canRead("1002")).isTrue();
        assertThat(operator.selectUser("1002")).isEqualTo("1002");
        assertThatThrownBy(() -> operator.requireProject("unassigned")).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> state.authenticate(devKey("1000")).requirePermission("runtime.view")).isInstanceOf(ServerException.class);
    }
}
