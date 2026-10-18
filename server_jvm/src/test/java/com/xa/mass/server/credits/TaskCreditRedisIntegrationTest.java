package com.xa.mass.server.credits;

import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.*;
import com.xa.mass.server.task.credit.*;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.user.UserDirectory;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("redis-owner")
class TaskCreditRedisIntegrationTest {
    RedisTestScope scope;
    RedisClient redis;
    CreditAccountStore accounts;
    TaskCreditStartStore starts;
    TaskCreditService service;
    UserDirectory users;
    TaskDescriptor task;
    @BeforeEach void setup() throws Exception {
        scope = RedisTestScope.create("task_credit"); redis = RedisClient.create(REDIS_URL);
        users = CreditHttpTest.directory();
        accounts = spy(new CreditAccountStore(redis, scope.keyspace()));
        starts = new TaskCreditStartStore(redis, scope.keyspace());
        service = new TaskCreditService(starts, accounts, users);
        task = new TaskDescriptor("task", "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "50", "maxRetryTimes", "3"), List.of(), "test",
                Map.of("server.projectId", "app-checks", "server.createdAtMillis", "1", "userId", "1001", "server.inputOwner", "app-checks"));
    }
    @AfterEach void cleanup() {
        starts.close(); accounts.close();
        try (var connection = redis.connect()) { scope.cleanup(connection.sync()); }
        redis.shutdown();
    }
    TaskCreditConfirmation confirmation(long seeds, long amount) { return new TaskCreditConfirmation(UUID.randomUUID().toString(), seeds, amount); }
    BusinessCharge business(long seeds) { return new BusinessCharge("app-checks", task.taskId(), "check", 2, seeds); }
    void fund(long amount) { new CreditService(users, accounts).adjust(users.resolveCaller("1000", "admin"), "1001", new Adjustment(UUID.randomUUID().toString(), amount, "proof")); }

    @Test void lostDebitResponseReconcilesAcrossRestartWithoutAnotherCharge() {
        fund(100);
        var caller = users.resolveCaller("1000", "admin");
        var confirmation = confirmation(7, 14);
        doAnswer(call -> { call.callRealMethod(); throw new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "proof.lostResponse", null, null); })
                .when(accounts).debit(anyString(), anyString(), any());
        assertThatThrownBy(() -> service.start(caller, task, 2, confirmation, business(7))).isInstanceOf(ServerException.class);
        assertThat(service.observe(caller, task, confirmation.requestId()).receipt().status()).isEqualTo("applied");
        starts.close(); accounts.close();
        starts = new TaskCreditStartStore(redis, scope.keyspace()); accounts = new CreditAccountStore(redis, scope.keyspace());
        service = new TaskCreditService(starts, accounts, users);
        var recovered = service.retained(caller, task, 2, confirmation);
        service.continueStart(recovered);
        assertThat(accounts.account("1001").balance()).isEqualTo(86);
        assertThat(accounts.account("1000").balance()).isZero();
        assertThat(accounts.transactions("1001", null, 50).response().entries()).hasSize(2);
        assertThat(service.start(caller, task, 2, confirmation(7, 14), business(7))).isEqualTo(recovered);
        assertThatThrownBy(() -> service.requireEditable(caller, task)).isInstanceOf(ServerException.class);
        service.beforeClose(caller, task); // No refund; closing and retention cannot change the account.
        starts.remove(task.taskId());
        assertThat(accounts.account("1001").balance()).isEqualTo(86);
        assertThat(accounts.debitReceipt("1001", recovered.ledgerRequestId()).status()).isEqualTo("applied");
    }
    @Test void insufficientAttemptRemainsRejectedAndNewExplicitAttemptCanStart() {
        var caller = users.resolveCaller("1001", "operator");
        var first = confirmation(7, 14);
        assertThatThrownBy(() -> service.start(caller, task, 2, first, business(7))).isInstanceOf(ServerException.class);
        service.requireEditable(caller, task);
        fund(100);
        assertThatThrownBy(() -> service.start(caller, task, 2, first, business(7))).isInstanceOf(ServerException.class);
        var second = confirmation(7, 14);
        service.start(caller, task, 2, second, business(7));
        assertThat(service.observe(caller, task, first.requestId()).receipt().status()).isEqualTo("rejected");
        assertThat(accounts.account("1001").balance()).isEqualTo(86);
    }
    @Test void pendingIntentBlocksImportDifferentAttemptAndClosureAndUnauthorizedCallerReadsNothing() {
        fund(100);
        var caller = users.resolveCaller("1001", "operator");
        var request = confirmation(7, 14);
        doThrow(new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "proof.beforeDebit", null, null)).when(accounts).debit(anyString(), anyString(), any());
        assertThatThrownBy(() -> service.start(caller, task, 2, request, business(7))).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.beforeClose(caller, task)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.requireEditable(caller, task)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.start(caller, task, 2, confirmation(7, 14), business(7))).isInstanceOf(ServerException.class);
        clearInvocations(accounts);
        assertThatThrownBy(() -> service.observe(users.resolveCaller("1002", "operator"), task, request.requestId())).isInstanceOf(ServerException.class);
        verifyNoInteractions(accounts);
    }
}
