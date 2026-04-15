package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenAttributesTest {

    @Test
    void setAttributesCopiesInputAndExposesReadOnlyView() {
        Token token = new Token();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("country", "us");

        token.setAttributes(input);
        input.put("country", "gb");

        assertEquals("us", token.getAttributes().get("country"));
        assertThrows(UnsupportedOperationException.class,
                () -> token.getAttributes().put("carrier", "tmo"));
    }

    @Test
    void nullAttributesReturnsEmptyMap() {
        Token token = new Token();

        token.setAttributes(null);

        assertTrue(token.getAttributes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> token.getAttributes().put("country", "us"));
    }
}
