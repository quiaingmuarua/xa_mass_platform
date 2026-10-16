package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AppCheckConfigurationStoreTest {
    @TempDir Path directory;
    Path path() { return directory.resolve("configuration"); }
    AppCheckConfigurationStore open() { return new AppCheckConfigurationStore(path(), AppCheckTestSettings::seed, AppCheckTestSettings.contracts()); }
    AppCheckSettings changed(String label, long window) {
        var original = AppCheckTestSettings.settings();
        var apps = new HashMap<>(original.checks());
        apps.put("app-a-registration", AppCheckTestSettings.simulated(label, "app-a-sim"));
        return new AppCheckSettings(original.typeDefaults(), apps,
                Map.of("app-a-sim", new WindowLimit(window, 15), "app-b-sim", original.poolGroups().get("app-b-sim")));
    }
    String fingerprint(Map<String, Object> view) { return (String) ((Map<?, ?>) view.get("saved")).get("fingerprint"); }
    String fingerprint(AppCheckSettings settings) { return new AppCheckConfigurationStore.Snapshot(settings).fingerprint(); }
    String initialFingerprint() { return fingerprint(AppCheckTestSettings.settings()); }

    @Test void lostCommitAcknowledgmentIsUnknownAndReadbackConfirmsTheWholeDocument() throws Exception {
        try (var store = open()) {
            var field = AppCheckConfigurationStore.class.getDeclaredField("connection");
            field.setAccessible(true);
            var actual = (java.sql.Connection) field.get(store);
            var commits = new java.util.concurrent.atomic.AtomicInteger();
            var witness = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        try {
                            var result = method.invoke(actual, args);
                            if (method.getName().equals("commit")) {
                                commits.incrementAndGet();
                                throw new java.sql.SQLException("commit acknowledgement lost");
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            field.set(store, witness);
            var target = changed("Committed", 60000);
            assertThatThrownBy(() -> store.save(initialFingerprint(), target.toMap())).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(503));
            var view = store.view();
            assertThat(fingerprint(view)).isEqualTo(fingerprint(target));
            assertThat(((Map<?, ?>) view.get("saved")).get("configuration")).isEqualTo(target.toMap());
            assertThat(store.effective().fingerprint()).isEqualTo(initialFingerprint());
            assertThat(commits.get()).isEqualTo(1);
        }
    }

    @Test void filePersistsAndSeedIsNotReadAgainWhileEffectiveSnapshotStaysFrozen() throws Exception {
        var target = changed("Renamed A", 120000);
        try (var store = open()) {
            assertThat(fingerprint(store.save(initialFingerprint(), AppCheckTestSettings.settings().toMap()))).isEqualTo(initialFingerprint());
            var updated = store.save(initialFingerprint(), target.toMap());
            assertThat(updated).containsEntry("restartRequired", true).containsEntry("newScopeRequired", true);
            assertThat(fingerprint(updated)).isEqualTo(fingerprint(target));
            assertThat(store.effective().configuration().requireCheck("app-a-registration").label()).isEqualTo("App A 模拟注册");
            assertThat(fingerprint(store.save(fingerprint(target), target.toMap()))).isEqualTo(fingerprint(target));
            assertThatThrownBy(() -> store.save(initialFingerprint(), target.toMap())).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(409));
        }
        try (var restored = new AppCheckConfigurationStore(path(), () -> { throw new AssertionError("seed must not be read"); }, AppCheckTestSettings.contracts())) {
            assertThat(restored.effective().configuration()).isEqualTo(target);
            assertThat(restored.view()).containsEntry("restartRequired", false).containsEntry("newScopeRequired", false);
            try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path().toString().replace('\\', '/'), "sa", "");
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("APP_CHECK_CONFIGURATION");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test void concurrentWholeConfigurationWritesHaveOneWinnerAndNoPartialMerge() throws Exception {
        try (var first = open(); var second = open(); var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var targets = List.of(changed("first", 60000), changed("second", 60000));
            var stores = List.of(first, second);
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                results.add(executor.submit(() -> {
                    gate.await();
                    try { stores.get(index).save(initialFingerprint(), targets.get(index).toMap()); return 200; }
                    catch (AppCheckConfigurationException failure) { return failure.status; }
                }));
            }
            gate.countDown();
            assertThat(List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(fingerprint(first.view())).isIn(fingerprint(targets.get(0)), fingerprint(targets.get(1)));
            assertThat(first.view()).isEqualTo(second.view());
        }
    }

    @Test void additionIsAcceptedButRemovalAndRebindingAreRejectedWithoutWriting() {
        var initial = AppCheckTestSettings.settings();
        try (var store = open()) {
            var removed = new AppCheckSettings(initial.typeDefaults(), Map.of("app-a-registration", initial.requireCheck("app-a-registration")),
                    Map.of("app-a-sim", initial.poolGroups().get("app-a-sim")));
            assertThatThrownBy(() -> store.save(initialFingerprint(), removed.toMap())).hasMessageContaining("不可删除、换类型或换绑");
            var rebound = new AppCheckSettings(initial.typeDefaults(), Map.of(
                    "app-a-registration", AppCheckTestSettings.simulated("A", "changed"), "app-b-registration", initial.requireCheck("app-b-registration")),
                    Map.of("changed", initial.poolGroups().get("app-a-sim"), "app-b-sim", initial.poolGroups().get("app-b-sim")));
            assertThatThrownBy(() -> store.save(initialFingerprint(), rebound.toMap())).hasMessageContaining("不可删除、换类型或换绑");
            assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint());
            var apps = new HashMap<>(initial.checks());
            var originalCheck = apps.get("app-a-registration");
            apps.put("app-a-registration", new AppCheckSettings.Check(originalCheck.label(), originalCheck.workerGroupId(),
                    originalCheck.execution(), AppCheckContracts.REGISTRATION, Map.of()));
            assertThatThrownBy(() -> store.save(initialFingerprint(), new AppCheckSettings(initial.typeDefaults(), apps, initial.poolGroups()).toMap()))
                    .hasMessageContaining("换类型");
            apps.put("app-a-registration", originalCheck);
            apps.put("app-c", AppCheckTestSettings.simulated("App C", "app-c-sim"));
            var groups = new HashMap<>(initial.poolGroups()); groups.put("app-c-sim", new WindowLimit(60000, 8));
            var update = store.save(initialFingerprint(), new AppCheckSettings(initial.typeDefaults(), apps, groups).toMap());
            assertThat(update).containsEntry("restartRequired", true).containsEntry("newScopeRequired", false);
            assertThat(store.effective().configuration().checks()).hasSize(5);
        }
    }

    @Test void badStoredDocumentDoesNotFallBackToSeed() throws Exception {
        try (var store = open()) { assertThat(fingerprint(store.view())).isEqualTo(initialFingerprint()); }
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path().toString().replace('\\', '/'), "sa", "");
             var statement = connection.createStatement()) { statement.executeUpdate("UPDATE app_check_configuration SET document = '{}' "); }
        assertThatThrownBy(this::open).isInstanceOf(AppCheckConfigurationException.class);
    }

    @Test void strictModelRejectsUnknownFieldsAndMissingLimitsButAllowsSharedGroups() {
        var input = new HashMap<String, Object>(Jsons.parseObject(AppCheckTestSettings.seed()));
        input.put("priority", 99);
        assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).hasMessageContaining("未知字段");
        input.remove("priority"); input.put("poolGroups", Map.of());
        assertThatThrownBy(() -> AppCheckSettings.parse(input, AppCheckTestSettings.contracts())).hasMessageContaining("缺少窗口参数");
        var initial = AppCheckTestSettings.settings();
        var duplicate = new HashMap<>(initial.checks());
        duplicate.put("same-group-check", initial.requireCheck("app-a-registration"));
        assertThat(new AppCheckSettings(initial.typeDefaults(), duplicate, initial.poolGroups()).workerGroupIds())
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
            var changed = store.save(initialFingerprint(), changed("Rename", 60000).toMap());
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
