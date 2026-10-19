package com.xa.mass.integration.androidworkerproof;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidWorkerBatchChecksTest {
    @Test void vectorsAreIndependentAndCsvKeepsQuotedUnicodeColumns() {
        assertEquals(123, new HashSet<>(AndroidWorkerBatchChecksMain.seeds()).size());
        assertEquals("0cc175b9c0f1b6a831c399e269772661", AndroidWorkerBatchChecksMain.md5("a"));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", AndroidWorkerBatchChecksMain.md5("abc"));
        assertEquals("a7bac2239fcdcb3a067903d8077c4a07", AndroidWorkerBatchChecksMain.md5("中文"));
        assertEquals(List.of(List.of("种子", "判定"), List.of("a,\"b\"", "满足")),
                AndroidWorkerBatchChecksMain.csv("\uFEFF\"种子\",\"判定\"\r\n\"a,\"\"b\"\"\",\"满足\"\r\n"));
        assertThrows(ProofFailure.class, () -> AndroidWorkerBatchChecksMain.csv("\"unfinished"));
    }
}
