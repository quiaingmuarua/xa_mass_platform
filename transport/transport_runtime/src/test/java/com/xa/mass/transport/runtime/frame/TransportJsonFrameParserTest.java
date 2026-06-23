package com.xa.mass.transport.runtime.frame;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportJsonFrameParserTest {

    private final TransportJsonFrameParser parser = new TransportJsonFrameParser();

    @Test
    void parsesOnlyJsonObjects() {
        JsonObject parsed = parser.parseObject("{\"messageId\":\"msg-1\"}");

        assertEquals("msg-1", parser.readString(parsed, "messageId"));
        assertNull(parser.parseObject("[\"not-object\"]"));
        assertNull(parser.parseObject("{\"messageId\":\"broken\""));
    }

    @Test
    void readsTypedFieldsWithoutThrowingOnInvalidValues() {
        JsonObject frame = parser.parseObject("""
                {
                  "blank": "   ",
                  "text": "  value  ",
                  "truth": true,
                  "object": {}
                }
                """);

        assertEquals("value", parser.readString(frame, "text"));
        assertNull(parser.readString(frame, "blank"));
        assertNull(parser.readString(frame, "missing"));
        assertNull(parser.readString(frame, "object"));
        assertTrue(parser.readBoolean(frame, "truth"));
        assertNull(parser.readBoolean(frame, "object"));
    }

    @Test
    void writesJsonObjectsAndStringMaps() {
        JsonObject frame = new JsonObject();
        frame.addProperty("kind", "ACTION");

        assertEquals("{\"kind\":\"ACTION\"}", parser.toJson(frame));
        assertEquals("{\"code\":\"INVALID\"}", parser.toJson(Map.of("code", "INVALID")));
    }
}
