package com.xa.mass.server.task.credit;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.credits.CreditAccountStore;
import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.credit.TaskCreditStartStore.Start;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.user.UserDirectory;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Request-driven admission only: no approval owner, automatic retry, or refund policy. */
@Service
public final class TaskCreditService {
    private final TaskCreditStartStore starts;
    private final CreditAccountStore accounts;
    private final UserDirectory users;
    public TaskCreditService(TaskCreditStartStore starts, CreditAccountStore accounts, UserDirectory users) {
        this.starts = starts; this.accounts = accounts; this.users = users;
    }
    public record Observation(Start start, CreditAdjustmentResponse receipt) {}
    public @Nullable Observation observe(TaskCaller caller, TaskDescriptor task, @Nullable String requestId) {
        caller.requireTask(task);
        var start = starts.read(task.taskId(), requestId);
        if (start == null) return null;
        if (!start.userId().equals(TaskAttribution.require(task.metadata()).userId())) throw unavailable("Stored charge owner differs");
        var receipt = accounts.debitReceipt(start.userId(), start.ledgerRequestId());
        if (receipt.transaction() != null && (!start.business().equals(receipt.transaction().business())
                || !start.actorUserId().equals(receipt.transaction().actorUserId())
                || !start.userId().equals(receipt.transaction().userId()))) throw unavailable("Stored charge receipt differs");
        return new Observation(start, receipt);
    }
    public void requireEditable(TaskCaller caller, TaskDescriptor task) {
        var previous = observe(caller, task, null);
        if (previous != null && !previous.receipt().status().equals("rejected"))
            throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskCredits.import", "启动扣分已受理，不能继续导入；请核对原启动操作", null);
    }
    public void beforeClose(TaskCaller caller, TaskDescriptor task) {
        var previous = observe(caller, task, null);
        if (previous != null && previous.receipt().status().equals("not_observed")) throw unavailable("请先核对或继续原启动请求，再关闭任务");
    }
    /** Returns a retained attempt when it can be replayed; callers must not recompute its price from current configuration. */
    public @Nullable Start retained(TaskCaller caller, TaskDescriptor task, long itemCount, TaskCreditConfirmation confirmation) {
        var previous = observe(caller, task, confirmation.requestId());
        if (previous != null) {
            confirm(previous.start(), caller, itemCount, confirmation, true);
            if (previous.receipt().status().equals("rejected")) rejected(previous.receipt());
            return previous.start();
        }
        var active = observe(caller, task, null);
        if (active == null || active.receipt().status().equals("rejected")) return null;
        if (!active.receipt().status().equals("applied")) throw unavailable("已有启动请求未确认，请使用原 requestId 核对");
        confirm(active.start(), caller, itemCount, confirmation, false);
        return active.start();
    }
    public Start start(TaskCaller caller, TaskDescriptor task, long itemCount, TaskCreditConfirmation confirmation, BusinessCharge business) {
        caller.requireTask(task);
        var retained = retained(caller, task, itemCount, confirmation);
        if (retained != null) return debit(retained);
        String owner = TaskAttribution.require(task.metadata()).userId();
        if (owner == null || !users.containsUser(owner))
            throw new ServerException(ServerErrorCode.CREDIT_ACCOUNT_NOT_FOUND, "taskCredits.start", "Task owner is absent from the user directory", null);
        if (!task.taskId().equals(business.taskId())) throw new IllegalArgumentException("Charge Task differs");
        var proposed = new Start(confirmation.requestId(), UUID.randomUUID().toString(), owner, caller.userId(), itemCount, business);
        confirm(proposed, caller, itemCount, confirmation, true);
        var previous = starts.read(task.taskId(), null);
        var reserved = starts.reserve(task.taskId(), proposed, previous == null ? null : previous.requestId());
        confirm(reserved, caller, itemCount, confirmation, true);
        if (!reserved.business().equals(business)) conflict();
        return debit(reserved);
    }
    public Start continueStart(Start start) { return debit(start); }
    private Start debit(Start start) {
        var result = accounts.debit(start.userId(), start.actorUserId(), new Debit(start.ledgerRequestId(), start.business()));
        if (result.status().equals("rejected")) rejected(result);
        return start;
    }
    private static void confirm(Start start, TaskCaller caller, long itemCount, TaskCreditConfirmation confirmation, boolean sameAttempt) {
        if (start.itemCount() != itemCount || start.business().quantity() != confirmation.expectedSeedCount()
                || start.business().amount() != confirmation.expectedAmount()
                || sameAttempt && !start.actorUserId().equals(caller.userId())) conflict();
    }
    private static void conflict() { throw new ServerException(ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "taskCredits.confirm", "启动请求或费用确认与原操作不一致", null); }
    private static void rejected(CreditAdjustmentResponse receipt) {
        throw new ServerException(receipt.rejectionCode().equals("insufficient_balance") ? ServerErrorCode.CREDIT_BALANCE_INSUFFICIENT
                : ServerErrorCode.CREDIT_BALANCE_LIMIT, "taskCredits.debit", null, null);
    }
    private static ServerException unavailable(String message) { return new ServerException(ServerErrorCode.CREDIT_UNAVAILABLE, "taskCredits.observe", message, null); }
}
