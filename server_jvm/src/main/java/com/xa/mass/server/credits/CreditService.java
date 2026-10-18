package com.xa.mass.server.credits;

import com.xa.mass.server.credits.CreditModels.*;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.user.UserDirectory;
import org.springframework.stereotype.Service;

@Service
public final class CreditService {
    private final UserDirectory users;
    private final CreditAccountStore accounts;
    public CreditService(UserDirectory users, CreditAccountStore accounts) { this.users = users; this.accounts = accounts; }

    public CreditAccountResponse account(TaskCaller caller, String userId) {
        userId = readTarget(caller, userId);
        boolean present = users.containsUser(userId);
        var state = accounts.account(userId);
        requireKnown(present, state.exists());
        return new CreditAccountResponse(userId, state.balance(), state.sequence(), state.sequence() == 0 ? null : state.updatedAtMillis(), present);
    }
    public CreditTransactionsResponse transactions(TaskCaller caller, String userId, String cursor, int limit) {
        userId = readTarget(caller, userId);
        boolean present = users.containsUser(userId);
        var page = accounts.transactions(userId, cursor, limit);
        requireKnown(present, page.exists());
        return page.response();
    }
    public CreditAdjustmentResponse adjustment(TaskCaller caller, String userId, String requestId) {
        userId = readTarget(caller, userId);
        requestId = CreditModels.requestId(requestId);
        boolean present = users.containsUser(userId);
        var receipt = accounts.adjustment(userId, requestId);
        requireKnown(present, receipt.exists());
        return receipt.response();
    }
    public CreditAdjustmentResponse adjust(TaskCaller caller, String userId, Adjustment input) {
        caller.requirePermission("credits.manage");
        userId = CreditModels.userId(userId);
        requireKnown(users.containsUser(userId), false);
        var result = accounts.adjust(userId, caller.userId(), input);
        if (result.status().equals("rejected")) {
            var code = result.rejectionCode().equals("insufficient_balance")
                    ? ServerErrorCode.CREDIT_BALANCE_INSUFFICIENT : ServerErrorCode.CREDIT_BALANCE_LIMIT;
            throw new ServerException(code, "credits.adjust", null, null);
        }
        return result;
    }
    private static String readTarget(TaskCaller caller, String userId) {
        caller.requirePermission("credits.view");
        String target = CreditModels.userId(userId);
        if (!target.equals(caller.userId())) caller.requirePermission("credits.manage");
        return target;
    }
    private static void requireKnown(boolean directoryUser, boolean accountExists) {
        if (!directoryUser && !accountExists)
            throw new ServerException(ServerErrorCode.CREDIT_ACCOUNT_NOT_FOUND, "credits.target", null, null);
    }
}
