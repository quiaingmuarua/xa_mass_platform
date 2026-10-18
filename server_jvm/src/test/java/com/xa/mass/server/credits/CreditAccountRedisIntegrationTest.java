package com.xa.mass.server.credits;

import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.*;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.user.UserDirectory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static com.xa.mass.server.credits.CreditModels.MAX_POINTS;
import static org.assertj.core.api.Assertions.*;

@Tag("redis-owner")
class CreditAccountRedisIntegrationTest {
    RedisTestScope scope;
    RedisClient redis;
    StatefulRedisConnection<String, String> connection;
    CreditAccountStore store;
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(1234567890L), ZoneOffset.UTC);
    @BeforeEach void start() {
        scope = RedisTestScope.create("server_credits");
        redis = RedisClient.create(REDIS_URL);
        connection = redis.connect();
        store = new CreditAccountStore(redis, scope.keyspace(), clock);
    }
    @AfterEach void stop() {
        store.close(); scope.cleanup(connection.sync()); connection.close(); redis.shutdown();
    }
    Adjustment input(long delta) { return new Adjustment(UUID.randomUUID().toString(), delta, "  manual reason  "); }
    CreditAdjustmentResponse adjust(long delta) { return store.adjust("1001", "1000", input(delta)); }
    @Test void readDoesNotCreateAndRejectionsRemainRejectedAfterLaterCredits() {
        assertThat(store.account("1001").exists()).isFalse();
        assertThat(store.transactions("1001", null, 50).response().entries()).isEmpty();
        assertThat(connection.sync().exists(store.key("1001"))).isZero();
        var rejected = input(-1);
        assertThat(store.adjust("1001", "1000", rejected).rejectionCode()).isEqualTo("insufficient_balance");
        assertThat(store.account("1001").sequence()).isZero();
        adjust(100);
        assertThat(store.adjust("1001", "1000", rejected).status()).isEqualTo("rejected");
        assertThat(store.adjustment("1001", rejected.requestId()).response().balanceAtDecision()).isZero();
        assertThat(store.account("1001").balance()).isEqualTo(100);
        assertThat(store.transactions("1001", null, 50).response().entries()).hasSize(1);
    }
    @Test void sameIdentityReplaysAndChangedContentOrActorConflicts() {
        var request = input(100);
        var original = store.adjust("1001", "1000", request);
        assertThat(original.transaction().reason()).isEqualTo("manual reason");
        adjust(-30);
        assertThat(store.adjust("1001", "1000", request)).isEqualTo(original);
        for (var other : List.of(new Adjustment(request.requestId(), 101, request.reason()), new Adjustment(request.requestId(), 100, "other")))
            assertCode(() -> store.adjust("1001", "1000", other), ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT);
        assertCode(() -> store.adjust("1001", "1002", request), ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT);
        assertThat(store.account("1001").balance()).isEqualTo(70);
        assertThat(store.account("1001").sequence()).isEqualTo(2);
        assertThat(store.adjust("1002", "1000", request).status()).isEqualTo("applied");
    }
    @Test void safeIntegerBoundariesRemainExactInStorageAndJson() {
        var row = adjust(MAX_POINTS).transaction();
        assertThat(row.delta()).isEqualTo(MAX_POINTS);
        assertThat(row.balanceAfter()).isEqualTo(MAX_POINTS);
        assertThat(adjust(1).rejectionCode()).isEqualTo("balance_limit");
        assertThat(store.account("1001").balance()).isEqualTo(MAX_POINTS);
        assertThat(adjust(-MAX_POINTS).transaction().balanceAfter()).isZero();
        assertThat(store.transactions("1001", null, 50).response().entries()).extracting(CreditTransaction::delta)
                .containsExactly(-MAX_POINTS, MAX_POINTS);
    }
    @Test void concurrentDebitsAndDuplicateSubmissionsAreAtomic() throws Exception {
        adjust(100);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futures = IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> { gate.await(); return adjust(-60); })).toList();
            gate.countDown();
            var statuses = new ArrayList<String>();
            for (var result : futures) statuses.add(result.get().status());
            assertThat(statuses).containsExactlyInAnyOrder("applied", "rejected");
            assertThat(store.account("1001").balance()).isEqualTo(40);
            var request = input(1);
            var replays = executor.invokeAll(IntStream.range(0, 20).mapToObj(i -> (Callable<CreditAdjustmentResponse>) () -> store.adjust("1001", "1000", request)).toList());
            for (var replay : replays) assertThat(replay.get().transaction().sequence()).isEqualTo(3);
            var independent = executor.invokeAll(IntStream.range(0, 40).mapToObj(i -> (Callable<CreditAdjustmentResponse>) () -> adjust(i % 2 == 0 ? 1 : -1)).toList());
            for (var result : independent) assertThat(result.get().status()).isEqualTo("applied");
            assertThat(store.account("1001").balance()).isEqualTo(41);
            assertThat(store.account("1001").sequence()).isEqualTo(43);
        }
    }
    @Test void pagesUseSequencesEvenWithIdenticalTimestampsAndConcurrentNewRecords() {
        for (int i = 0; i < 107; i++) adjust(1);
        var first = store.transactions("1001", null, 50).response();
        adjust(1);
        var second = store.transactions("1001", first.nextCursor(), 50).response();
        var third = store.transactions("1001", second.nextCursor(), 50).response();
        var sequences = new ArrayList<Long>();
        for (var page : List.of(first, second, third)) page.entries().forEach(t -> sequences.add(t.sequence()));
        assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.iterate(107, i -> i - 1).limit(107).boxed().toList());
        assertThat(third.nextCursor()).isNull();
        assertCode(() -> store.transactions("1002", first.nextCursor(), 50), ServerErrorCode.INVALID_CREDIT_REQUEST);
        assertCode(() -> store.transactions("1001", "invalid", 50), ServerErrorCode.INVALID_CREDIT_REQUEST);
        assertCode(() -> store.transactions("1001", null, 101), ServerErrorCode.INVALID_CREDIT_REQUEST);
    }
    @Test void committedButLostResponseSurvivesReconnectionAndDirectoryReload() throws Exception {
        var request = input(100);
        // Redis commits, but the caller loses the returned value before receiving an HTTP response.
        assertThatThrownBy(() -> { store.adjust("1001", "1000", request); throw new java.io.IOException("response dropped"); })
                .isInstanceOf(java.io.IOException.class);
        store.close();
        store = new CreditAccountStore(redis, scope.keyspace(), clock);
        assertThat(store.adjustment("1001", request.requestId()).response().transaction().balanceAfter()).isEqualTo(100);
        assertThat(store.adjust("1001", "1000", request).transaction().sequence()).isEqualTo(1);
        var users = CreditHttpTest.directory();
        var admin = users.resolveCaller("1000", "admin");
        users.update(admin, "1001", new UserDirectory.UpdateUser("renamed", "admin", List.of()));
        assertThat(new CreditService(users, store).account(users.resolveCaller("1001", "admin"), "1001").balance()).isEqualTo(100);
        users.create(admin, new UserDirectory.User("tempuser", "Temporary", "operator", List.of()));
        var service = new CreditService(users, store);
        service.adjust(admin, "tempuser", input(7));
        var restarted = new CreditService(CreditHttpTest.directory(), store);
        assertThat(restarted.account(admin, "tempuser").balance()).isEqualTo(7);
        assertThat(restarted.account(admin, "tempuser").userPresent()).isFalse();
        assertCode(() -> restarted.adjust(admin, "tempuser", input(1)), ServerErrorCode.CREDIT_ACCOUNT_NOT_FOUND);
    }
    @Test void corruptStorageIsNotZeroAndValidationCannotPartiallyPublish() {
        var commands = connection.sync();
        String key = store.key("1001");
        commands.set(key, "wrong-type");
        assertCode(() -> store.account("1001"), ServerErrorCode.CREDIT_UNAVAILABLE);
        assertCode(() -> adjust(1), ServerErrorCode.CREDIT_UNAVAILABLE);
        assertThat(commands.get(key)).isEqualTo("wrong-type");
        commands.unlink(key);
        adjust(9);
        var valid = commands.hgetall(key);
        for (var broken : List.of(Map.of("balance", "0"), Map.of("sequence", "1.5"), Map.of("transaction:1", "{}"))) {
            commands.hset(key, broken);
            var before = commands.hgetall(key);
            assertCode(() -> store.account("1001"), ServerErrorCode.CREDIT_UNAVAILABLE);
            assertCode(() -> adjust(1), ServerErrorCode.CREDIT_UNAVAILABLE);
            assertThat(commands.hgetall(key)).isEqualTo(before);
            commands.hset(key, valid);
        }
        commands.hset(key, "transaction:2", "unexpected");
        var before = commands.hgetall(key);
        assertCode(() -> adjust(1), ServerErrorCode.CREDIT_UNAVAILABLE);
        assertThat(commands.hgetall(key)).isEqualTo(before);
    }
    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ServerErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
