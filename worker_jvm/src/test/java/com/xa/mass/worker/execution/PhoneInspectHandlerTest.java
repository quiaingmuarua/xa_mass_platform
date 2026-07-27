package com.xa.mass.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PhoneInspectHandlerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final PhoneInspectHandler handler = new PhoneInspectHandler();

    @Test
    void validInternationalNumbersReturnStableInspection() throws Exception {
        assertEquals(
                """
                {"countryCallingCode":1,"e164":"+14155552671",\
                "isPossible":true,"isValid":true,"regionCode":"US"}\
                """,
                json.writeValueAsString(handler.execute(
                        payload("+14155552671")
                ))
        );
        assertEquals(
                """
                {"countryCallingCode":44,"e164":"+442083661177",\
                "isPossible":true,"isValid":true,"regionCode":"GB"}\
                """,
                json.writeValueAsString(handler.execute(
                        payload("+442083661177")
                ))
        );
    }

    @Test
    void invalidInputIsACompletedToolResult() {
        assertEquals(
                """
                {"countryCallingCode":1,"e164":"+12001230101",\
                "isPossible":true,"isValid":false,"regionCode":null}\
                """,
                json.writeValueAsString(
                        handler.inspectInternationalPhoneNumber(
                                "+12001230101"
                        )
                )
        );
        ObjectNode result = handler.inspectInternationalPhoneNumber(
                "not-a-phone-number"
        );
        assertEquals(
                """
                {"countryCallingCode":null,"e164":null,"isPossible":false,\
                "isValid":false,"regionCode":null}\
                """,
                json.writeValueAsString(result)
        );
    }

    @Test
    void missingPhoneNumberIsWorkerInputFailure() {
        assertThrows(
                WorkerInputException.class,
                () -> handler.execute(json.createObjectNode())
        );
    }

    private ObjectNode payload(String number) {
        ObjectNode payload = json.createObjectNode();
        payload.put("phoneNumber", number);
        return payload;
    }
}
