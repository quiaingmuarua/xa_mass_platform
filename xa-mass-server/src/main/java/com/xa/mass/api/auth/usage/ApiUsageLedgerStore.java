package com.xa.mass.api.auth.usage;

import java.util.List;

public interface ApiUsageLedgerStore {

    ApiUsageLedgerRecord append(ApiUsageLedgerRecord record);

    List<ApiUsageLedgerRecord> listByKeyId(String keyId);

    List<ApiUsageLedgerRecord> listByPrincipalId(String principalId);
}
