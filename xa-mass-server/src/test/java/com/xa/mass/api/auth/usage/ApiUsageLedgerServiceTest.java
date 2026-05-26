package com.xa.mass.api.auth.usage;

import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiUsageLedgerServiceTest {

    @Test
    void sameRequestIdCanRecordAcceptedAndFailedAfterAccept() {
        InMemoryApiUsageLedgerStore store = new InMemoryApiUsageLedgerStore();
        ApiUsageLedgerService service = new ApiUsageLedgerService(store);
        PrincipalContext principal = principal("ak-usage-1");

        service.recordAccepted(
                principal,
                ApiUsageOperation.TASK_ITEM_SYNC_APPEND,
                "crawlerApp",
                "crawler.fetch-page",
                "task-001",
                "msg-001",
                "request-001",
                1
        );
        service.recordFailedAfterAccept(
                principal,
                ApiUsageOperation.TASK_ITEM_SYNC_APPEND,
                "crawlerApp",
                "crawler.fetch-page",
                "task-001",
                "msg-001",
                "request-001",
                "IllegalStateException: bridge failed",
                400
        );
        service.recordFailedAfterAccept(
                principal,
                ApiUsageOperation.TASK_ITEM_SYNC_APPEND,
                "crawlerApp",
                "crawler.fetch-page",
                "task-001",
                "msg-001",
                "request-001",
                "IllegalStateException: bridge failed again",
                500
        );

        List<ApiUsageLedgerRecord> records = store.listByKeyId("ak-usage-1");
        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(record ->
                record.status() == ApiUsageStatus.ACCEPTED && record.units() == 1));
        ApiUsageLedgerRecord failed = records.stream()
                .filter(record -> record.status() == ApiUsageStatus.FAILED_AFTER_ACCEPT)
                .findFirst()
                .orElseThrow();
        assertEquals(0, failed.units());
        assertEquals("IllegalStateException: bridge failed", failed.failureReason());
        assertEquals(400, failed.failureStatus());
    }

    private PrincipalContext principal(String keyId) {
        return new PrincipalContext(
                "agent",
                null,
                "crawlerApp",
                List.of("task:create", "task:append", "task:view"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, keyId)
        );
    }
}
