package com.xa.mass.transport.runtime.frame;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Transport embedded-adapter JSON text-frame helper.
 */
public final class TransportJsonFrameParser {

    private static final Logger logger = LoggerFactory.getLogger(TransportJsonFrameParser.class);

    private final Gson gson;

    public TransportJsonFrameParser() {
        this(new Gson());
    }

    public TransportJsonFrameParser(Gson gson) {
        this.gson = gson;
    }

    public JsonObject parseObject(String json) {
        try {
            JsonElement element = gson.fromJson(json, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonSyntaxException ex) {
            logger.warn("Failed to parse transport JSON frame: {}", ex.getMessage());
            logger.debug("Malformed transport JSON frame", ex);
            return null;
        }
    }

    public String toJson(JsonObject frame) {
        return gson.toJson(frame);
    }

    public String toJson(Map<String, String> values) {
        return gson.toJson(values);
    }

    public String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(field).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    public Boolean readBoolean(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }
}
