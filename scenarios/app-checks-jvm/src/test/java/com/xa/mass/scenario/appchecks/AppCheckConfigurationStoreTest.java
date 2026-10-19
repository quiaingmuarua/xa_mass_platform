package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AppCheckConfigurationStoreTest {
    @Test @SuppressWarnings("unchecked")
    void groupingIsNormalizedAndCheckedAgainstTheSelectedContractBeforeSaving() {
        var input = Jsons.parseObject(AppCheckTestSettings.seed());
        var checks = (Map<String, Map<String,Object>>) input.get("checks");
        var execution = (Map<String,Object>) checks.get("md5-prefix").get("execution");
        execution.remove("seedsPerItem");
        assertThat(AppCheckSettings.parse(input, AppCheckTestSettings.contracts()).requireCheck("md5-prefix").execution().toMap())
                .containsEntry("seedsPerItem", 1);
        for (Object invalid : List.of(0, -1, 101, 1.5, "10", 4294967297L)) {
            execution.put("seedsPerItem", invalid);
            assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).isInstanceOf(IllegalArgumentException.class);
        }
        execution.put("seedsPerItem", 100);
        assertThat(AppCheckSettings.parse(input, AppCheckTestSettings.contracts()).requireCheck("md5-prefix").execution().seedsPerItem()).isEqualTo(100);
        var single = (Map<String,Object>) checks.get("uint32-prime").get("execution");
        single.put("seedsPerItem", 2);
        assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).hasMessageContaining("seedsPerItem");
        try (var store = open()) {
            assertThatThrownBy(() -> store.save(initialFingerprint(), input)).isInstanceOf(AppCheckConfigurationException.class);
            assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint());
        }
    }
    @TempDir Path directory;
    Path path() { return directory.resolve("configuration.json"); }
    AppCheckConfigurationStore open() { return new AppCheckConfigurationStore(path(), AppCheckTestSettings::seed, AppCheckTestSettings.contracts()); }
    AppCheckSettings changed(String label) {
        var original = AppCheckTestSettings.settings();
        var apps = new HashMap<>(original.checks());
        apps.put("app-a-registration", AppCheckTestSettings.simulated(label, "app-a-sim"));
        return new AppCheckSettings(original.typeDefaults(), apps);
    }
    String fingerprint(Map<String, Object> view) { return (String) ((Map<?, ?>) view.get("saved")).get("fingerprint"); }
    String fingerprint(AppCheckSettings settings) { return new AppCheckConfigurationStore.Snapshot(settings).fingerprint(); }
    String initialFingerprint() { return fingerprint(AppCheckTestSettings.settings()); }

    @Test void lostPublicationAcknowledgmentIsUnknownAndReadbackConfirmsTheWholeDocument() throws Exception {
        try (var initial = open()) { assertThat(initial.view()).containsEntry("restartRequired", false); }
        var publications = new java.util.concurrent.atomic.AtomicInteger();
        var files = new AppCheckConfigurationStore.FilePublication() {
            @Override void replace(Path pending, Path target) throws IOException {
                super.replace(pending, target);
                publications.incrementAndGet();
                throw new IOException("publication acknowledgement lost");
            }
        };
        try (var store = new AppCheckConfigurationStore(path(), AppCheckTestSettings::seed, AppCheckTestSettings.contracts(), files)) {
            var target = changed("Committed");
            assertThatThrownBy(() -> store.save(initialFingerprint(), target.toMap())).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(503));
            var view = store.view();
            assertThat(fingerprint(view)).isEqualTo(fingerprint(target));
            assertThat(((Map<?, ?>) view.get("saved")).get("configuration")).isEqualTo(target.toMap());
            assertThat(store.effective().fingerprint()).isEqualTo(initialFingerprint());
            assertThat(publications.get()).isEqualTo(1);
            assertNoPendingFiles();
        }
    }

    @Test void filePersistsAndSeedIsNotReadAgainWhileEffectiveSnapshotStaysFrozen() throws Exception {
        var target = changed("Renamed A");
        try (var store = open()) {
            assertThat(fingerprint(store.save(initialFingerprint(), AppCheckTestSettings.settings().toMap()))).isEqualTo(initialFingerprint());
            var updated = store.save(initialFingerprint(), target.toMap());
            assertThat(updated).containsEntry("restartRequired", true);
            assertThat(fingerprint(updated)).isEqualTo(fingerprint(target));
            assertThat(store.effective().configuration().requireCheck("app-a-registration").label()).isEqualTo("App A 模拟注册");
            assertThat(fingerprint(store.save(fingerprint(target), target.toMap()))).isEqualTo(fingerprint(target));
            assertThatThrownBy(() -> store.save(initialFingerprint(), target.toMap())).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(409));
        }
        try (var restored = new AppCheckConfigurationStore(path(), () -> { throw new AssertionError("seed must not be read"); }, AppCheckTestSettings.contracts())) {
            assertThat(restored.effective().configuration()).isEqualTo(target);
            assertThat(restored.view()).containsEntry("restartRequired", false);
            assertThat(Files.readString(path())).contains("\n  ").doesNotContain("fingerprint", "revision");
            assertThat(AppCheckSettings.parse(Jsons.parseObject(Files.readString(path())), AppCheckTestSettings.contracts())).isEqualTo(target);
            assertNoPendingFiles();
        }
    }

    @Test void concurrentWholeConfigurationWritesHaveOneWinnerAndNoPartialMerge() throws Exception {
        try (var store = open(); var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var targets = List.of(changed("first"), changed("second"));
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                results.add(executor.submit(() -> {
                    gate.await();
                    try { store.save(initialFingerprint(), targets.get(index).toMap()); return 200; }
                    catch (AppCheckConfigurationException failure) { return failure.status; }
                }));
            }
            gate.countDown();
            assertThat(List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(fingerprint(store.view())).isIn(fingerprint(targets.get(0)), fingerprint(targets.get(1)));
            assertNoPendingFiles();
        }
    }

    @Test void completeReplacementAllowsRemovalRenameRebindingAndTypeChangesOnlyForNextStartup() {
        var initial = AppCheckTestSettings.settings();
        var checks = new HashMap<>(initial.checks());
        checks.remove("app-b-registration");
        checks.put("renamed-md5", checks.remove("md5-prefix"));
        var original = checks.get("app-a-registration");
        checks.put("app-a-registration", new AppCheckSettings.Check("New registration", "external-group",
                original.execution(), AppCheckContracts.REGISTRATION, Map.of(), 1L));
        var replacement = new AppCheckSettings(initial.typeDefaults(), checks);
        try (var store = open()) {
            var update = store.save(initialFingerprint(), replacement.toMap());
            assertThat(update).containsEntry("restartRequired", true);
            assertThat(store.effective().configuration()).isEqualTo(initial);
            assertThat(((Map<?, ?>) update.get("saved")).get("configuration")).isEqualTo(replacement.toMap());
        }
        try (var restored = open()) {
            assertThat(restored.effective().configuration()).isEqualTo(replacement);
            assertThat(restored.view()).containsEntry("restartRequired", false);
            assertThat(restored.effective().configuration().checks()).doesNotContainKeys("app-b-registration", "md5-prefix");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void invalidReplacementNeverChangesTheSavedFile() throws Exception {
        try (var store = open()) {
            byte[] original = Files.readAllBytes(path());
            for (String kind : List.of("empty", "unknown", "parameters", "grouping")) {
                var document = Jsons.parseObject(AppCheckTestSettings.seed());
                var checks = (Map<String, Map<String,Object>>) document.get("checks");
                var check = checks.get("app-a-registration");
                switch (kind) {
                    case "empty" -> checks.clear();
                    case "unknown" -> check.put("checkType", "missing-contract");
                    case "parameters" -> { check.put("checkType", AppCheckContracts.REGISTRATION); check.put("parameters", Map.of("ranges", Map.of())); }
                    case "grouping" -> ((Map<String,Object>) check.get("execution")).put("seedsPerItem", 10);
                    default -> throw new AssertionError(kind);
                }
                assertThatThrownBy(() -> store.save(initialFingerprint(), document))
                        .isInstanceOfSatisfying(AppCheckConfigurationException.class, failure -> assertThat(failure.status).isEqualTo(400));
                assertThat(Files.readAllBytes(path())).isEqualTo(original);
                assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint());
                assertNoPendingFiles();
            }
        }
    }

    @Test void badStoredDocumentDoesNotFallBackToSeed() throws Exception {
        try (var store = open()) { assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint()); }
        for (String bad : List.of("", "{", "{}", "[]")) {
            Files.writeString(path(), bad);
            assertThatThrownBy(() -> new AppCheckConfigurationStore(path(), () -> {
                throw new AssertionError("existing files must never read seed");
            }, AppCheckTestSettings.contracts())).isInstanceOf(AppCheckConfigurationException.class);
            assertThat(Files.readString(path())).isEqualTo(bad);
        }
        Files.write(path(), new byte[]{(byte) 0xC3, 0x28});
        assertThatThrownBy(this::open).isInstanceOf(AppCheckConfigurationException.class);
        Files.writeString(path(), AppCheckTestSettings.seed());
        try (var reopened = open()) { assertThat(fingerprint(reopened.view())).isEqualTo(initialFingerprint()); }
    }

    @Test void sameContentDoesNotRewriteTheFile() throws Exception {
        try (var store = open()) {
            byte[] before = Files.readAllBytes(path());
            var oldTime = java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000L);
            Files.setLastModifiedTime(path(), oldTime);
            store.save(initialFingerprint(), AppCheckTestSettings.settings().toMap());
            assertThat(Files.readAllBytes(path())).isEqualTo(before);
            assertThat(Files.getLastModifiedTime(path())).isEqualTo(oldTime);
        }
    }

    @Test void anotherOwnerAndAnotherProcessCannotOpenTheFileUntilClose() throws Exception {
        try (var store = open()) {
            assertThat(probe()).isEqualTo(2);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(this::open).isInstanceOf(AppCheckConfigurationException.class);
                assertThat(probe()).as("rejected local open must preserve the OS lock").isEqualTo(2);
            }
            assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint());
            store.save(initialFingerprint(), changed("Saved while locked").toMap());
            assertThat(probe()).as("atomic document replacement must preserve the lock").isEqualTo(2);
        }
        assertThat(probe()).isZero();
        try (var reopened = open()) {
            assertThat(fingerprint(reopened.view())).isEqualTo(fingerprint(changed("Saved while locked")));
        }
    }

    @Test void concurrentOpenHasOneOwnerAndKeepsTheCrossProcessLock() throws Exception {
        var owners = new ConcurrentLinkedQueue<AppCheckConfigurationStore>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var attempts = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                attempts.add(executor.submit(() -> {
                    gate.await();
                    try { owners.add(open()); return true; }
                    catch (AppCheckConfigurationException failure) {
                        assertThat(failure.getCause()).isInstanceOf(IOException.class).hasMessageContaining("already owned");
                        return false;
                    }
                }));
            }
            gate.countDown();
            assertThat(List.of(attempts.get(0).get(5, TimeUnit.SECONDS), attempts.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(owners).hasSize(1);
            assertThat(probe()).isEqualTo(2);
        } finally { owners.forEach(AppCheckConfigurationStore::close); }
        assertThat(probe()).isZero();
    }

    @Test void failedInitializationReleasesItsLockWithoutLeavingAPartialDocument() throws Exception {
        assertThatThrownBy(() -> new AppCheckConfigurationStore(path(), () -> "{}", AppCheckTestSettings.contracts()))
                .isInstanceOf(AppCheckConfigurationException.class);
        assertThat(path()).doesNotExist();
        assertThatThrownBy(() -> new AppCheckConfigurationStore(path(), () -> {
            throw new AssertionError("seed initialization failed");
        }, AppCheckTestSettings.contracts())).isInstanceOf(AssertionError.class);
        try (var store = open()) { assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint()); }
        assertNoPendingFiles();
    }

    @Test void partialWriteAndUnavailableAtomicReplacementPreserveTheOriginal() throws Exception {
        try (var initial = open()) { assertThat(initial.effective().fingerprint()).isEqualTo(initialFingerprint()); }
        byte[] before = Files.readAllBytes(path());
        var failures = List.of(new AppCheckConfigurationStore.FilePublication() {
            @Override void write(Path pending, byte[] document) throws IOException {
                Files.writeString(pending, "{partial");
                throw new IOException("write failed");
            }
        }, new AppCheckConfigurationStore.FilePublication() {
            @Override void replace(Path pending, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(pending.toString(), target.toString(), "unsupported");
            }
        });
        for (var files : failures) {
            try (var store = new AppCheckConfigurationStore(path(), AppCheckTestSettings::seed, AppCheckTestSettings.contracts(), files)) {
                assertThatThrownBy(() -> store.save(initialFingerprint(), changed("Unconfirmed").toMap()))
                        .isInstanceOfSatisfying(AppCheckConfigurationException.class, failure -> assertThat(failure.status).isEqualTo(503));
                assertThat(Files.readAllBytes(path())).isEqualTo(before);
                assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint());
                assertNoPendingFiles();
            }
        }
    }

    @Test void runtimeDeletionOrCorruptionNeverReseedsOrChangesTheEffectiveSnapshot() throws Exception {
        try (var store = open()) {
            Files.delete(path());
            assertUnavailable(store);
            assertThat(path()).doesNotExist();
            Files.writeString(path(), "{");
            assertUnavailable(store);
            assertThat(Files.readString(path())).isEqualTo("{");
            assertThat(store.effective().fingerprint()).isEqualTo(initialFingerprint());
        }
    }

    private void assertUnavailable(AppCheckConfigurationStore store) {
        assertThatThrownBy(store::view).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                failure -> assertThat(failure.status).isEqualTo(503));
        assertThatThrownBy(() -> store.save(initialFingerprint(), changed("Not saved").toMap()))
                .isInstanceOfSatisfying(AppCheckConfigurationException.class, failure -> assertThat(failure.status).isEqualTo(503));
    }

    private void assertNoPendingFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertThat(files.map(file -> file.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("configuration.json", "configuration.json.lock");
        }
    }

    private int probe() throws Exception {
        Path arguments = directory.resolve("probe.args");
        String classpath = System.getProperty("xa.mass.appchecks.test.classpath");
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n"
                + AppCheckConfigurationLockProbe.class.getName() + "\n\"" + path().toString().replace("\\", "\\\\") + "\"\n");
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new ProcessBuilder(executable, "@" + arguments).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("file owner probe completes").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(output).containsAnyOf("ACQUIRED", "BUSY");
            return process.exitValue();
        } finally { process.destroyForcibly(); Files.deleteIfExists(arguments); }
    }

    @Test void strictModelRejectsOldResourceConfigurationAndAllowsSharedGroups() {
        var input = new HashMap<String, Object>(Jsons.parseObject(AppCheckTestSettings.seed()));
        input.put("priority", 99);
        assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).hasMessageContaining("未知字段");
        input.remove("priority"); input.put("poolGroups", Map.of());
        assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).hasMessageContaining("未知字段");
        var initial = AppCheckTestSettings.settings();
        var duplicate = new HashMap<>(initial.checks());
        duplicate.put("same-group-check", initial.requireCheck("app-a-registration"));
        assertThat(new AppCheckSettings(initial.typeDefaults(), duplicate).workerGroupIds())
                .containsExactly("app-a-sim", "app-b-sim", "demo-sim");

    }

    @Test void contentFingerprintIgnoresFormattingObjectOrderAndEquivalentNumericSpelling() {
        var original = AppCheckTestSettings.settings();
        var reordered = new LinkedHashMap<>(original.toMap());
        var entries = new ArrayList<>(original.checks().entrySet()); Collections.reverse(entries);
        var checks = new LinkedHashMap<String,Object>(); entries.forEach(entry -> checks.put(entry.getKey(), entry.getValue().toMap()));
        reordered.put("checks", checks);
        String json = Jsons.toJson(reordered).replace("1000", "1000.0").replace(",", ",\n");
        try (var store = open()) {
            var view = store.save(initialFingerprint(), Jsons.parseObject(json));
            assertThat(fingerprint(view)).matches("[0-9a-f]{64}").isEqualTo(initialFingerprint());
            assertThat(view).containsEntry("restartRequired", false);
            assertThat(store.effective().configuration().checks().keySet()).containsExactly("app-a-registration", "app-b-registration", "md5-prefix", "original-carrier", "uint32-prime");
            var changed = store.save(initialFingerprint(), changed("Rename").toMap());
            // Content guard, not edit history: returning to identical content restores the same token.
            var reverted = store.save(fingerprint(changed), original.toMap());
            assertThat(fingerprint(reverted)).isEqualTo(initialFingerprint());
            assertThat(reverted).containsEntry("restartRequired", false);
            assertThatThrownBy(() -> store.save("not-a-fingerprint", original.toMap()))
                    .isInstanceOfSatisfying(AppCheckConfigurationException.class, error -> assertThat(error.status).isEqualTo(400));
        }
    }

    @Test void checkIdsOnlyExistAsKeysAndOldArrayOrRedundantIdsAreRejected() {
        var original = AppCheckTestSettings.settings();
        var document = new HashMap<String, Object>(original.toMap());
        document.put("checks", List.of(original.requireCheck("app-a-registration").toMap()));
        assertThatThrownBy(() -> AppCheckSettings.parse(document, AppCheckTestSettings.contracts())).hasMessageContaining("checks: 必须是对象");
        var redundant = new HashMap<>(original.requireCheck("app-a-registration").toMap());
        redundant.put("checkId", "app-a-registration");
        document.put("checks", Map.of("app-a-registration", redundant));
        assertThatThrownBy(() -> AppCheckSettings.parse(document, AppCheckTestSettings.contracts())).hasMessageContaining("checks.app-a-registration.checkId: 未知字段");
        document.put("checks", Map.of(" ", original.requireCheck("app-a-registration").toMap()));
        assertThatThrownBy(() -> AppCheckSettings.parse(document, AppCheckTestSettings.contracts())).hasMessageContaining("checks: 无效键");
    }

    @Test void storageClosureCannotInvalidateTheIndependentEffectiveSettings() {
        var store = open();
        var snapshot = store.effective();
        store.close();
        assertThatThrownBy(store::view).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                failure -> assertThat(failure.status).isEqualTo(503));
        assertThat(snapshot.configuration().requireCheck("app-a-registration").workerGroupId()).isEqualTo("app-a-sim");
    }
}
