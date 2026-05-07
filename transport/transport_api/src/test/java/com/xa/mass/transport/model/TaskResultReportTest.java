package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskResultReportTest {

    @Test
    void outputDetachesNestedMutableValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", "SUCCESS");
        List<Object> details = new ArrayList<>();
        details.add("first");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", nested);
        output.put("details", details);

        TaskResultReport report = new TaskResultReport(
                "task-1",
                "msg-1",
                true,
                "ok",
                null,
                output
        );

        nested.put("status", "FAILED");
        details.add("second");

        assertEquals("SUCCESS", assertInstanceOf(Map.class, report.getOutput().get("summary")).get("status"));
        assertEquals(List.of("first"), report.getOutput().get("details"));
    }

    @Test
    void outputRejectsUnsupportedNonJsonValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new TaskResultReport(
                "task-1",
                "msg-1",
                true,
                "ok",
                null,
                Map.of("unsupported", new Object())
        ));

        assertEquals(
                "output.unsupported contains unsupported non-JSON value type: java.lang.Object",
                error.getMessage()
        );
    }
}
