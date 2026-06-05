package com.xa.mass.api.auth.usage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InMemoryApiUsageLedgerStore implements ApiUsageLedgerStore {

    private final Map<String, ApiUsageLedgerRecord> byUsageId = new LinkedHashMap<>();

    @Override
    public synchronized ApiUsageLedgerRecord append(ApiUsageLedgerRecord record) {
        ApiUsageLedgerRecord normalized = Objects.requireNonNull(record, "record");
        if (byUsageId.containsKey(normalized.usageId())) {
            return byUsageId.get(normalized.usageId());
        }
        byUsageId.put(normalized.usageId(), normalized);
        return normalized;
    }

    @Override
    public synchronized List<ApiUsageLedgerRecord> listByKeyId(String keyId) {
        return sorted().stream()
                .filter(record -> Objects.equals(record.keyId(), keyId))
                .toList();
    }

    @Override
    public synchronized List<ApiUsageLedgerRecord> listByPrincipalId(String principalId) {
        return sorted().stream()
                .filter(record -> Objects.equals(record.principalId(), principalId))
                .toList();
    }

    private List<ApiUsageLedgerRecord> sorted() {
        return new ArrayList<>(byUsageId.values()).stream()
                .sorted(Comparator.comparing(ApiUsageLedgerRecord::createdAt)
                        .thenComparing(ApiUsageLedgerRecord::usageId))
                .toList();
    }
}
