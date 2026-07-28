package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StringTransformHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final StringTransformHandler handler =
            new StringTransformHandler();

    @Test
    void oneEventCodeSelectsAllSupportedOperations() throws Exception {
        assertEquals(
                "utility.string.transform",
                StringTransformHandler.EVENT_CODE
        );
        assertResult("BASE64", "hello", "aGVsbG8=");
        assertResult(
                "MD5",
                "hello",
                "5d41402abc4b2a76b9719d911017c592"
        );
        assertResult(
                "SHA1",
                "hello",
                "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        );
    }

    @Test
    void utf8AndEmptyStringsRemainValidBusinessInputs()
            throws Exception {
        assertResult("BASE64", "\u4f60\u597d", "5L2g5aW9");
        assertResult("BASE64", "", "");
        assertResult(
                "MD5",
                "",
                "d41d8cd98f00b204e9800998ecf8427e"
        );
    }

    @Test
    void missingOrUnknownOperationsAreWorkerInputFailures() {
        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(json.createObjectNode())
        );
        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(payload("md5", "hello"))
        );

        ObjectNode wrongValue = json.createObjectNode();
        wrongValue.put("operation", "MD5");
        wrongValue.put("value", 1);
        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(wrongValue)
        );
    }

    private void assertResult(
            String operation,
            String value,
            String expected
    ) throws Exception {
        assertEquals(
                "{\"operation\":\"" + operation
                        + "\",\"result\":\"" + expected + "\"}",
                json.writeValueAsString(
                        handler.execute(payload(operation, value))
                )
        );
    }

    private ObjectNode payload(String operation, String value) {
        ObjectNode payload = json.createObjectNode();
        payload.put("operation", operation);
        payload.put("value", value);
        return payload;
    }
}
