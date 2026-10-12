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
    AppCheckConfigurationStore open() { return new AppCheckConfigurationStore(path(), AppCheckTestSettings::seed); }
    AppCheckSettings changed(String label, long window) {
        var original = AppCheckTestSettings.settings();
        return new AppCheckSettings(original.simulationDefault(), List.of(
                new AppCheckSettings.Application("app-a", label, "app-a-sim"), original.applications().get(1)),
                Map.of("app-a-sim", new WindowLimit(window, 15), "app-b-sim", original.poolGroups().get("app-b-sim")));
    }
    long revision(Map<String, Object> view) { return ((Number) ((Map<?, ?>) view.get("saved")).get("revision")).longValue(); }

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
            assertThatThrownBy(() -> store.save(1, target)).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(503));
            var view = store.view();
            assertThat(revision(view)).isEqualTo(2);
            assertThat(((Map<?, ?>) view.get("saved")).get("configuration")).isEqualTo(target.toMap());
            assertThat(store.effective().revision()).isEqualTo(1);
            assertThat(commits.get()).isEqualTo(1);
        }
    }

    @Test void filePersistsAndSeedIsNotReadAgainWhileEffectiveSnapshotStaysFrozen() throws Exception {
        var target = changed("Renamed A", 120000);
        try (var store = open()) {
            assertThat(revision(store.save(1, AppCheckTestSettings.settings()))).isEqualTo(1);
            var updated = store.save(1, target);
            assertThat(updated).containsEntry("restartRequired", true).containsEntry("newScopeRequired", true);
            assertThat(revision(updated)).isEqualTo(2);
            assertThat(store.effective().configuration().requireApplication("app-a").label()).isEqualTo("App A");
            assertThat(revision(store.save(2, target))).isEqualTo(2);
            assertThatThrownBy(() -> store.save(1, target)).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                    failure -> assertThat(failure.status).isEqualTo(409));
        }
        try (var restored = new AppCheckConfigurationStore(path(), () -> { throw new AssertionError("seed must not be read"); })) {
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
                    try { stores.get(index).save(1, targets.get(index)); return 200; }
                    catch (AppCheckConfigurationException failure) { return failure.status; }
                }));
            }
            gate.countDown();
            assertThat(List.of(results.get(0).get(5, TimeUnit.SECONDS), results.get(1).get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(revision(first.view())).isEqualTo(2);
            assertThat(first.view()).isEqualTo(second.view());
        }
    }

    @Test void additionIsAcceptedButRemovalAndRebindingAreRejectedWithoutWriting() {
        var initial = AppCheckTestSettings.settings();
        try (var store = open()) {
            var removed = new AppCheckSettings(initial.simulationDefault(), List.of(initial.applications().getFirst()),
                    Map.of("app-a-sim", initial.poolGroups().get("app-a-sim")));
            assertThatThrownBy(() -> store.save(1, removed)).hasMessageContaining("不可删除或换绑");
            var rebound = new AppCheckSettings(initial.simulationDefault(), List.of(
                    new AppCheckSettings.Application("app-a", "A", "changed"), initial.applications().get(1)),
                    Map.of("changed", initial.poolGroups().get("app-a-sim"), "app-b-sim", initial.poolGroups().get("app-b-sim")));
            assertThatThrownBy(() -> store.save(1, rebound)).hasMessageContaining("不可删除或换绑");
            assertThat(revision(store.view())).isEqualTo(1);
            var apps = new ArrayList<>(initial.applications());
            apps.add(new AppCheckSettings.Application("app-c", "App C", "app-c-sim"));
            var groups = new HashMap<>(initial.poolGroups()); groups.put("app-c-sim", new WindowLimit(60000, 8));
            var update = store.save(1, new AppCheckSettings(initial.simulationDefault(), apps, groups));
            assertThat(update).containsEntry("restartRequired", true).containsEntry("newScopeRequired", false);
            assertThat(store.effective().configuration().applications()).hasSize(2);
        }
    }

    @Test void badStoredFormatOrDocumentDoesNotFallBackToSeed() throws Exception {
        try (var store = open()) { assertThat(revision(store.view())).isEqualTo(1); }
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path().toString().replace('\\', '/'), "sa", "");
             var statement = connection.createStatement()) { statement.executeUpdate("UPDATE app_check_configuration SET format_version = 99"); }
        assertThatThrownBy(this::open).hasRootCauseMessage("Unsupported App Checks configuration format");
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path().toString().replace('\\', '/'), "sa", "");
             var statement = connection.createStatement()) { statement.executeUpdate("UPDATE app_check_configuration SET format_version = 1, document = '{}' "); }
        assertThatThrownBy(this::open).isInstanceOf(AppCheckConfigurationException.class);
    }

    @Test void strictModelRejectsUnknownFieldsDuplicateBindingsAndMissingLimits() {
        var input = new HashMap<String, Object>(Jsons.parseObject(AppCheckTestSettings.seed()));
        input.put("priority", 99);
        assertThatThrownBy(() -> AppCheckSettings.parse(input)).hasMessageContaining("未知字段");
        input.remove("priority"); input.put("poolGroups", Map.of());
        assertThatThrownBy(() -> AppCheckSettings.parse(input)).hasMessageContaining("完整对应");
        var initial = AppCheckTestSettings.settings();
        assertThatThrownBy(() -> new AppCheckSettings(initial.simulationDefault(),
                List.of(initial.applications().getFirst(), initial.applications().getFirst()), initial.poolGroups())).hasMessageContaining("唯一");
    }

    @Test void storageClosureCannotInvalidateTheIndependentEffectiveSettings() {
        var store = open();
        var snapshot = store.effective();
        store.close();
        assertThatThrownBy(store::view).isInstanceOfSatisfying(AppCheckConfigurationException.class,
                failure -> assertThat(failure.status).isEqualTo(503));
        assertThat(snapshot.configuration().requireApplication("app-a").workerGroupId()).isEqualTo("app-a-sim");
    }
}
