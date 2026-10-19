package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.credits.CreditModels;
import com.xa.mass.server.credits.CreditModels.BusinessCharge;
import com.xa.mass.server.credits.CreditModels.CreditTransaction;
import com.xa.mass.server.credits.CreditService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.FiniteTaskApprovalAdmission;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.credit.TaskCreditConfirmation;
import com.xa.mass.server.task.credit.TaskCreditService;
import com.xa.mass.server.task.directory.TaskAttribution;
import org.jspecify.annotations.Nullable;

/** App Checks owns seed interpretation and the immutable price, not account or approval mutations. */
public final class AppCheckCredits implements FiniteTaskApprovalAdmission {
    private final TaskDataService data;
    private final TaskCreditService starts;
    private final CreditService accounts;
    private final ProjectTaskQueryService queries;
    private final OperationGuard operations;
    private final AppCheckContracts contracts;
    public AppCheckCredits(TaskDataService data, TaskCreditService starts, CreditService accounts,
            ProjectTaskQueryService queries, OperationGuard operations, AppCheckContracts contracts) {
        this.data = data; this.starts = starts; this.accounts = accounts; this.queries = queries;
        this.operations = operations; this.contracts = contracts;
    }
    @Override public String inputOwner() { return AppCheckTaskService.PROJECT; }
    public record View(String taskId, String userId, long unitPrice, long itemCount, long seedCount, long amount,
                       long balance, String status, @Nullable String requestId, String requestStatus,
                       @Nullable String rejectionCode, @Nullable CreditTransaction transaction) {}
    private record Quote(long itemCount, long seedCount, long unitPrice) { long amount() { return unitPrice * seedCount; } }

    public View view(TaskCaller caller, String taskId, @Nullable String requestId) {
        caller.requireProject(inputOwner());
        return operations.taskMutation(taskId, () -> {
            var task = data.requireFiniteInputTask(caller, inputOwner(), taskId);
            var state = queries.get(caller, inputOwner(), taskId).scoreBand();
            var observed = starts.observe(caller, task, requestId);
            var active = requestId == null ? observed : starts.observe(caller, task, null);
            String owner = TaskAttribution.require(task.metadata()).userId();
            long balance = accounts.account(caller, owner).balance();
            var saved = observed != null ? observed : active;
            Quote quote;
            if (saved != null) {
                var start = saved.start();
                quote = new Quote(start.itemCount(), start.business().quantity(), start.business().unitPrice());
                // A fresh confirmation after a rejected attempt reflects any subsequent imports.
                if (requestId == null && saved.receipt().status().equals("rejected") && "pre_review".equals(state)) quote = quote(caller, task);
            } else quote = "pre_review".equals(state) ? quote(caller, task) : new Quote(0, 0, unitPrice(task));
            String receiptStatus = observed == null ? "not_observed" : observed.receipt().status();
            String status = observed == null ? "ready" : switch (receiptStatus) {
                case "applied" -> "pre_review".equals(state) ? "charged" : "terminal".equals(state) ? "closed" : "started";
                case "rejected" -> "rejected";
                default -> "debit_unconfirmed";
            };
            return new View(taskId, owner, quote.unitPrice(), quote.itemCount(), quote.seedCount(), quote.amount(), balance,
                    status, observed == null ? requestId : observed.start().requestId(), receiptStatus,
                    observed == null ? null : observed.receipt().rejectionCode(), observed == null ? null : observed.receipt().transaction());
        });
    }
    @Override public void beforeApprove(TaskCaller caller, TaskDescriptor task, long expectedItemCount, @Nullable TaskCreditConfirmation confirmation) {
        caller.requireInput(task, inputOwner());
        if (confirmation == null) throw invalid("App Checks 启动需要费用确认");
        var retained = starts.retained(caller, task, expectedItemCount, confirmation);
        if (retained != null) {
            var observed = starts.observe(caller, task, retained.requestId());
            if (observed == null || !observed.receipt().status().equals("applied")) {
                var current = quote(caller, task);
                if (current.itemCount() != retained.itemCount() || current.seedCount() != retained.business().quantity()
                        || current.unitPrice() != retained.business().unitPrice())
                    throw new ServerException(ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "appChecks.credits", "输入与原启动快照不一致", null);
            }
            starts.continueStart(retained); return;
        }
        var quote = quote(caller, task);
        if (quote.itemCount() != expectedItemCount || quote.seedCount() != confirmation.expectedSeedCount()
                || quote.amount() != confirmation.expectedAmount())
            throw new ServerException(ServerErrorCode.CREDIT_ADJUSTMENT_CONFLICT, "appChecks.credits", "输入数量或费用已变化，请重新确认", null);
        starts.start(caller, task, expectedItemCount, confirmation, new BusinessCharge(inputOwner(), task.taskId(),
                task.metadata().get("checkId"), quote.unitPrice(), quote.seedCount()));
    }
    @Override public void beforeClose(TaskCaller caller, TaskDescriptor task) { starts.beforeClose(caller, task); }
    public void requireEditable(TaskCaller caller, String taskId) {
        starts.requireEditable(caller, data.requireFiniteInputTask(caller, inputOwner(), taskId));
    }
    private Quote quote(TaskCaller caller, TaskDescriptor task) {
        var snapshot = AppCheckTaskSnapshot.read(task.workerGroupId(), task.metadata(), contracts);
        long price = unitPrice(task);
        long[] seeds = {0};
        long items = data.visitFiniteTaskInputs(caller, inputOwner(), task.taskId(), item -> {
            var input = snapshot.inputSeeds(item.payload());
            if (!snapshot.eventCode().equals(item.eventCode()) || !snapshot.messageId(input).equals(item.messageId()))
                throw invalid("存储输入与任务检查配置不一致");
            if (seeds[0] > CreditModels.MAX_POINTS / price - input.size()) throw invalid("积分总额超过安全整数范围");
            seeds[0] += input.size();
        });
        return new Quote(items, seeds[0], price);
    }
    private static long unitPrice(TaskDescriptor task) {
        try {
            var value = task.metadata().get("unitPrice");
            if (value == null || !value.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
            long price = Long.parseLong(value);
            if (price > CreditModels.MAX_POINTS) throw new IllegalArgumentException();
            return price;
        } catch (IllegalArgumentException invalid) { throw invalid("任务缺少有效的固定单价"); }
    }
    private static ServerException invalid(String message) { return new ServerException(ServerErrorCode.INVALID_CREDIT_REQUEST, "appChecks.credits", message, null); }
}
