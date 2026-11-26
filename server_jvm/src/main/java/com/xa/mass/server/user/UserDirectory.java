package com.xa.mass.server.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xa.mass.server.assembly.controlplane.ControlPlaneDatabase;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskUserId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One Server-process directory of roles, users, Project grants and access-key hashes.
 *
 * <p>When {@code xa.mass.control-plane.database-path} is configured, the directory is persisted in the
 * control-plane SQLite database (bootstrapping once from {@code xa.mass.users.seed} when empty) and
 * serves all reads and authentication from an immutable in-memory snapshot with zero SQLite I/O on the
 * request hot path. Otherwise, a {@code file:} seed is owned by this process via an exclusive sidecar
 * lock and atomic full-document publication, and classpath seeds remain read-only.
 */
@Service
public final class UserDirectory implements AutoCloseable {
    public static final String DEFAULT_SEED = "classpath:users/development-directory.json";
    public static final String ACCESS_KEY_PREFIX = "xak_";
    private static final System.Logger LOG = System.getLogger(UserDirectory.class.getName());
    private static final Pattern ACCESS_KEY = Pattern.compile("xak_[A-Za-z0-9_-]{8,128}");
    private static final Pattern KEY_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    // Reserve a canonical path before opening its lock channel; the JVM rejects overlapping locks itself.
    private static final Set<Path> OWNED_FILES = ConcurrentHashMap.newKeySet();

    private final ObjectMapper mapper;
    private final ProjectDirectory projects;
    private final @Nullable Path file;
    private final @Nullable FileChannel lockChannel;
    private final FilePublication publication;
    private final @Nullable SqliteUserDirectoryStore sqliteStore;
    private Snapshot snapshot;
    private boolean closed;

    @Autowired
    public UserDirectory(ObjectMapper mapper, ResourceLoader resources,
            @Value("${xa.mass.users.seed:}") String seed,
            @Value("${xa.mass.users.require-configured-seed:false}") boolean requireConfiguredSeed,
            ProjectDirectory projects,
            @Autowired(required = false) @Nullable ControlPlaneDatabase controlPlane) throws IOException {
        this.mapper = Objects.requireNonNull(mapper);
        this.projects = Objects.requireNonNull(projects);
        this.publication = new FilePublication();
        if (controlPlane != null && controlPlane.isEnabled()) {
            this.file = null;
            this.lockChannel = null;
            this.sqliteStore = new SqliteUserDirectoryStore(controlPlane, mapper);
            this.snapshot = initializeSqlite(this.sqliteStore,
                    () -> readSeedBytes(resources, seed, requireConfiguredSeed));
            return;
        }
        this.sqliteStore = null;
        var initialized = initializeFileOrClasspath(location(resources, seed, requireConfiguredSeed));
        this.file = initialized.file();
        this.lockChannel = initialized.lockChannel();
        this.snapshot = initialized.snapshot();
    }

    public UserDirectory(ObjectMapper mapper, ResourceLoader resources, String seed,
            boolean requireConfiguredSeed, ProjectDirectory projects) throws IOException {
        this(mapper, resources, seed, requireConfiguredSeed, projects, null);
    }

    /** Control-plane SQLite persisted directory, bootstrapping once from {@code bootstrapSeed} when empty. */
    public UserDirectory(ObjectMapper mapper, ControlPlaneDatabase controlPlane,
            @Nullable Resource bootstrapSeed, boolean requireConfiguredSeed,
            ProjectDirectory projects) throws IOException {
        this(mapper, requireEnabledStore(controlPlane, mapper), bootstrapSeed, requireConfiguredSeed, projects);
    }

    UserDirectory(ObjectMapper mapper, SqliteUserDirectoryStore sqliteStore,
            @Nullable Resource bootstrapSeed, boolean requireConfiguredSeed,
            ProjectDirectory projects) throws IOException {
        this.mapper = Objects.requireNonNull(mapper);
        this.projects = Objects.requireNonNull(projects);
        this.publication = new FilePublication();
        this.file = null;
        this.lockChannel = null;
        this.sqliteStore = Objects.requireNonNull(sqliteStore);
        this.snapshot = initializeSqlite(sqliteStore, () -> readResourceSeed(bootstrapSeed, requireConfiguredSeed));
    }

    /** Read-only directory, used for built-in and test seeds. */
    public UserDirectory(ObjectMapper mapper, Resource seed, ProjectDirectory projects) throws IOException {
        this(mapper, projects, new Location(null, seed), new FilePublication());
    }

    /** Persisted directory owned by this process. */
    public UserDirectory(ObjectMapper mapper, Path file, ProjectDirectory projects) throws IOException {
        this(mapper, projects, new Location(file, null), new FilePublication());
    }

    UserDirectory(ObjectMapper mapper, Path file, ProjectDirectory projects, FilePublication publication) throws IOException {
        this(mapper, projects, new Location(file, null), publication);
    }

    private UserDirectory(ObjectMapper mapper, ProjectDirectory projects, Location location, FilePublication publication)
            throws IOException {
        this.mapper = Objects.requireNonNull(mapper);
        this.projects = Objects.requireNonNull(projects);
        this.publication = Objects.requireNonNull(publication);
        this.sqliteStore = null;
        var initialized = initializeFileOrClasspath(location);
        this.file = initialized.file();
        this.lockChannel = initialized.lockChannel();
        this.snapshot = initialized.snapshot();
    }

    private static SqliteUserDirectoryStore requireEnabledStore(ControlPlaneDatabase controlPlane, ObjectMapper mapper) {
        Objects.requireNonNull(controlPlane, "controlPlane");
        if (!controlPlane.isEnabled()) {
            throw new IllegalArgumentException("Control-plane database must be enabled for SQLite UserDirectory");
        }
        return new SqliteUserDirectoryStore(controlPlane, mapper);
    }

    private Snapshot initializeSqlite(SqliteUserDirectoryStore store, SeedBytesSupplier bootstrapSupplier)
            throws IOException {
        if (!store.isEmpty()) {
            Snapshot loaded = validateSeed(store.loadAll(), "");
            LOG.log(System.Logger.Level.INFO, "Loaded user directory from control-plane SQLite ({0} users)",
                    loaded.users().size());
            return loaded;
        }
        Snapshot seeded = parse(bootstrapSupplier.read());
        store.bootstrap(toSeed(seeded.roles().values(), seeded.users().values()));
        LOG.log(System.Logger.Level.INFO, "Bootstrapped control-plane SQLite user directory from seed ({0} users)",
                seeded.users().size());
        return new Snapshot("", seeded.roles(), seeded.users(), seeded.userByKeyHash());
    }

    private InitializedFile initializeFileOrClasspath(Location location) throws IOException {
        if (location.file() == null) {
            try (var stream = Objects.requireNonNull(location.resource()).getInputStream()) {
                return new InitializedFile(null, null, parse(stream.readAllBytes()));
            }
        }
        Path reserved = null;
        FileChannel opened = null;
        try {
            Path absolute = location.file().toAbsolutePath().normalize();
            Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) canonical = canonical.toRealPath();
            if (!OWNED_FILES.add(canonical)) throw new IOException("User directory file is already owned by this process");
            reserved = canonical;
            // The lock file is never renamed with the document, so every process locks the same file.
            opened = FileChannel.open(canonical.resolveSibling(canonical.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = opened.tryLock();
            if (lock == null) throw new IOException("User directory file is already owned by another process");
            return new InitializedFile(canonical, opened, parse(Files.readAllBytes(canonical)));
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) try { opened.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (reserved != null) OWNED_FILES.remove(reserved);
            throw failure;
        }
    }

    private static byte[] readSeedBytes(ResourceLoader resources, String seed, boolean requireConfiguredSeed)
            throws IOException {
        var resolved = location(resources, seed, requireConfiguredSeed);
        if (resolved.file() != null) {
            return Files.readAllBytes(resolved.file().toAbsolutePath().normalize());
        }
        try (var stream = Objects.requireNonNull(resolved.resource()).getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private static byte[] readResourceSeed(@Nullable Resource bootstrapSeed, boolean requireConfiguredSeed)
            throws IOException {
        if (bootstrapSeed == null) {
            if (requireConfiguredSeed) {
                throw new IllegalStateException("xa.mass.users.seed must be configured explicitly for this profile");
            }
            LOG.log(System.Logger.Level.WARNING, "Built-in development users with public access keys are active; use only for local runs");
            var defaultResource = new org.springframework.core.io.ClassPathResource("users/development-directory.json");
            try (var stream = defaultResource.getInputStream()) {
                return stream.readAllBytes();
            }
        }
        try (var stream = bootstrapSeed.getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private static Location location(ResourceLoader resources, String seed, boolean requireConfiguredSeed) throws IOException {
        String configured = seed == null ? "" : seed.strip();
        if (configured.isEmpty()) {
            if (requireConfiguredSeed)
                throw new IllegalStateException("xa.mass.users.seed must be configured explicitly for this profile");
            configured = DEFAULT_SEED;
        }
        if (configured.equals(DEFAULT_SEED))
            LOG.log(System.Logger.Level.WARNING, "Built-in development users with public access keys are active; use only for local runs");
        if (configured.startsWith("file:")) return new Location(resources.getResource(configured).getFile().toPath(), null);
        return new Location(null, resources.getResource(configured));
    }

    public synchronized List<Role> roles() { return List.copyOf(snapshot.roles().values()); }

    public synchronized List<UserView> users() {
        return snapshot.users().values().stream().map(Entry::view).toList();
    }

    /** Point lookup only; account I/O must not hold the directory monitor. */
    public synchronized boolean containsUser(String userId) { return snapshot.users().containsKey(userId); }

    /** One request's immutable identity and grants, resolved from its access key under the same gate as writes. */
    public synchronized TaskCaller authenticate(@Nullable String accessKey) {
        if (accessKey == null || !ACCESS_KEY.matcher(accessKey).matches()) throw unauthenticated();
        String userId = snapshot.userByKeyHash().get(hash(accessKey));
        if (userId == null) throw unauthenticated();
        var entry = snapshot.users().get(userId);
        return TaskCaller.fromDirectory(entry.user(), snapshot.roles().get(entry.user().role()));
    }

    /** The authenticated caller's current directory record and role grants. */
    public synchronized CurrentUser current(TaskCaller caller) {
        var entry = caller.userId() == null ? null : snapshot.users().get(caller.userId());
        if (entry == null) throw unauthenticated();
        var user = entry.user();
        return new CurrentUser(user.userId(), user.displayName(), snapshot.roles().get(user.role()), user.projectIds());
    }

    public synchronized UserView create(TaskCaller caller, User input) {
        caller.requirePermission("users.manage");
        var user = normalize(input);
        if (snapshot.users().containsKey(user.userId())) throw rejected("User already exists");
        user.projectIds().forEach(projects::require);
        var next = new LinkedHashMap<>(snapshot.users());
        next.put(user.userId(), new Entry(user, null));
        return persist("create", next, () -> Objects.requireNonNull(sqliteStore).createUser(user, null))
                .users().get(user.userId()).view();
    }

    public synchronized UserView update(TaskCaller caller, String userId, UpdateUser input) {
        var existing = target(caller, userId);
        var user = normalize(new User(userId, input.displayName(), input.role(), input.projectIds()));
        user.projectIds().forEach(projects::require);
        var next = new LinkedHashMap<>(snapshot.users());
        next.put(userId, new Entry(user, existing.accessKeySha256()));
        return persist("update", next, () -> Objects.requireNonNull(sqliteStore).updateUser(user))
                .users().get(userId).view();
    }

    /** Replaces the user's key; the returned plaintext is the only copy and is never logged or stored. */
    public synchronized IssuedAccessKey issueAccessKey(TaskCaller caller, String userId) {
        var existing = target(caller, userId);
        String accessKey;
        do { accessKey = newAccessKey(); } while (snapshot.userByKeyHash().containsKey(hash(accessKey)));
        String keyHash = hash(accessKey);
        var next = new LinkedHashMap<>(snapshot.users());
        next.put(userId, new Entry(existing.user(), keyHash));
        persist("issueAccessKey", next, () -> Objects.requireNonNull(sqliteStore).updateAccessKeyHash(userId, keyHash));
        return new IssuedAccessKey(userId, accessKey);
    }

    public synchronized UserView clearAccessKey(TaskCaller caller, String userId) {
        var existing = target(caller, userId);
        var next = new LinkedHashMap<>(snapshot.users());
        next.put(userId, new Entry(existing.user(), null));
        return persist("clearAccessKey", next, () -> Objects.requireNonNull(sqliteStore).updateAccessKeyHash(userId, null))
                .users().get(userId).view();
    }

    @Override public synchronized void close() throws IOException {
        if (closed || lockChannel == null) { closed = true; return; }
        closed = true;
        try { lockChannel.close(); } finally { OWNED_FILES.remove(file); }
    }

    private Entry target(TaskCaller caller, String userId) {
        caller.requirePermission("users.manage");
        var existing = snapshot.users().get(userId);
        if (existing == null) throw rejected("User does not exist");
        if (Objects.equals(userId, caller.userId())) throw rejected("Use another administrator to edit the current user");
        return existing;
    }

    private Snapshot persist(String operation, Map<String, Entry> users, SqliteWrite sqliteWrite) {
        if (sqliteStore != null) {
            if (closed) {
                throw new ServerException(ServerErrorCode.USER_DIRECTORY_CONFLICT, "userDirectory." + operation,
                        "User directory is closed", null);
            }
            final Snapshot next;
            try {
                next = validateSeed(toSeed(snapshot.roles().values(), users.values()), "");
            } catch (IllegalArgumentException invalid) {
                throw new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory." + operation, null, invalid);
            }
            try {
                sqliteWrite.execute();
            } catch (IOException failure) {
                throw new ServerException(ServerErrorCode.USER_DIRECTORY_UNAVAILABLE, "userDirectory." + operation, null, failure);
            }
            snapshot = next;
            return next;
        }
        if (file == null || closed)
            throw new ServerException(ServerErrorCode.USER_DIRECTORY_CONFLICT, "userDirectory." + operation, "User directory seed is read-only", null);
        final byte[] document;
        final Snapshot next;
        try {
            document = render(snapshot.roles().values(), users.values());
            next = parse(document);
        } catch (IllegalArgumentException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory." + operation, null, invalid);
        }
        try {
            if (!fingerprint(Files.readAllBytes(file)).equals(snapshot.fingerprint()))
                throw new ServerException(ServerErrorCode.USER_DIRECTORY_CONFLICT, "userDirectory." + operation,
                        "User directory file changed outside this Server", null);
            Path pending = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            try {
                publication.write(pending, document);
                publication.replace(pending, file);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException failure) {
            throw new ServerException(ServerErrorCode.USER_DIRECTORY_UNAVAILABLE, "userDirectory." + operation, null, failure);
        }
        snapshot = next;
        return next;
    }

    private Snapshot parse(byte[] document) {
        final Seed seed;
        try {
            seed = mapper.readerFor(Seed.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(document);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid user directory document", invalid);
        }
        return validateSeed(seed, fingerprint(document));
    }

    private static Snapshot validateSeed(Seed seed, String fingerprint) {
        Objects.requireNonNull(seed, "seed");
        var roles = new LinkedHashMap<String, Role>();
        for (var role : Objects.requireNonNull(seed.roles(), "roles")) {
            if (!Set.of("admin", "operator").contains(role.id()) || roles.putIfAbsent(role.id(), role) != null)
                throw new IllegalArgumentException("Invalid or duplicate role");
        }
        if (!roles.keySet().equals(Set.of("admin", "operator"))) throw new IllegalArgumentException("Both roles are required");
        var users = new LinkedHashMap<String, Entry>();
        var keyIndex = new LinkedHashMap<String, String>();
        for (var input : Objects.requireNonNull(seed.users(), "users")) {
            var user = validate(new User(input.userId(), input.displayName(), input.role(), input.projectIds()), roles);
            if (users.putIfAbsent(user.userId(), new Entry(user, input.accessKeySha256())) != null)
                throw new IllegalArgumentException("Duplicate user");
            if (input.accessKeySha256() != null) {
                if (!KEY_HASH.matcher(input.accessKeySha256()).matches()) throw new IllegalArgumentException("Invalid access key hash");
                if (keyIndex.putIfAbsent(input.accessKeySha256(), user.userId()) != null)
                    throw new IllegalArgumentException("Duplicate access key hash");
            }
        }
        if (users.values().stream().noneMatch(entry -> entry.user().role().equals("admin")))
            throw new IllegalArgumentException("An admin is required");
        return new Snapshot(fingerprint, java.util.Collections.unmodifiableMap(roles),
                java.util.Collections.unmodifiableMap(users), Map.copyOf(keyIndex));
    }

    private static Seed toSeed(java.util.Collection<Role> roles, java.util.Collection<Entry> users) {
        var entries = new ArrayList<SeedUser>();
        for (var entry : users) {
            var user = entry.user();
            entries.add(new SeedUser(user.userId(), user.displayName(), user.role(), user.projectIds(), entry.accessKeySha256()));
        }
        return new Seed(List.copyOf(roles), entries);
    }

    private byte[] render(java.util.Collection<Role> roles, java.util.Collection<Entry> users) {
        return (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toSeed(roles, users)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private User normalize(User input) {
        try { return validate(input, snapshot.roles()); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory.validate", null, invalid);
        }
    }

    private static User validate(User input, Map<String, Role> roles) {
        String id = TaskUserId.normalize(input.userId());
        String name = Objects.requireNonNull(input.displayName()).strip();
        if (name.isEmpty() || name.length() > 80 || !roles.containsKey(input.role())) throw new IllegalArgumentException("Invalid user");
        var grants = List.copyOf(input.projectIds());
        if (grants.size() > 100 || grants.stream().anyMatch(String::isBlank) || grants.stream().distinct().count() != grants.size())
            throw new IllegalArgumentException("Invalid Project grants");
        if (roles.get(input.role()).projectScope().equals("all") && !grants.isEmpty())
            throw new IllegalArgumentException("Role already grants all Projects");
        return new User(id, name, input.role(), grants);
    }

    static String newAccessKey() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return ACCESS_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public static String hash(String accessKey) {
        return HexFormat.of().formatHex(sha256(accessKey.getBytes(StandardCharsets.UTF_8)));
    }

    private static String fingerprint(byte[] document) { return HexFormat.of().formatHex(sha256(document)); }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static ServerException unauthenticated() {
        return new ServerException(ServerErrorCode.UNAUTHENTICATED, "userDirectory.authenticate", null, null);
    }

    private static ServerException rejected(String reason) {
        return new ServerException(ServerErrorCode.INVALID_USER_REQUEST, "userDirectory.write", reason, null);
    }

    /** Package-local file boundary for publication fault proofs. No independent state or lifecycle. */
    static class FilePublication {
        void write(Path pending, byte[] document) throws IOException {
            try (var output = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(document);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
        }

        void replace(Path pending, Path target) throws IOException {
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface private interface SeedBytesSupplier { byte[] read() throws IOException; }
    @FunctionalInterface private interface SqliteWrite { void execute() throws IOException; }
    private record InitializedFile(@Nullable Path file, @Nullable FileChannel lockChannel, Snapshot snapshot) {}
    private record Location(@Nullable Path file, @Nullable Resource resource) {}
    private record Snapshot(String fingerprint, Map<String, Role> roles, Map<String, Entry> users, Map<String, String> userByKeyHash) {}
    private record Entry(User user, @Nullable String accessKeySha256) {
        UserView view() { return new UserView(user.userId(), user.displayName(), user.role(), user.projectIds(), accessKeySha256 != null); }
    }

    /** The persisted document; access-key hashes are not secret but are never returned by the API. */
    public record Seed(List<Role> roles, List<SeedUser> users) {}
    public record SeedUser(String userId, String displayName, String role, List<String> projectIds,
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String accessKeySha256) {}
    public record Role(String id, String label, List<String> permissions, String taskScope, String projectScope, String home) {
        public Role {
            permissions = List.copyOf(permissions);
            if (label == null || label.isBlank() || !Set.of("all", "own").contains(taskScope)
                    || !Set.of("all", "assigned").contains(projectScope) || home == null || !home.startsWith("/") || home.startsWith("//"))
                throw new IllegalArgumentException("Invalid role definition");
        }
    }
    public record User(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "[1-9a-z][0-9a-z]{3,8}") String userId,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "admin|operator") String role,
            @NotNull @Size(max = 100) List<@NotBlank String> projectIds) {}
    public record UpdateUser(@NotBlank @Size(max = 80) String displayName,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "admin|operator") String role,
            @NotNull @Size(max = 100) List<@NotBlank String> projectIds) {}
    public record UserView(String userId, String displayName, String role, List<String> projectIds, boolean hasAccessKey) {}
    public record CurrentUser(String userId, String displayName, Role role, List<String> projectIds) {}
    public record IssuedAccessKey(String userId, String accessKey) {}
}
