package com.xa.mass.mock.command.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Type;

/**
 * Minimal Gson-backed JSON helpers for the dev-app command package.
 */
public final class Jsons {

    private static final Gson GSON = new Gson();

    private Jsons() {
    }

    public static boolean usingGson() {
        return true;
    }

    public static String engineName() {
        return "gson";
    }

    public static String toJson(Object src) {
        return GSON.toJson(src);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    public static JsonObject toJsonObject(Object src) {
        return GSON.toJsonTree(src).getAsJsonObject();
    }

    public static JsonArray toJsonArray(Object src) {
        return GSON.toJsonTree(src).getAsJsonArray();
    }

    public static JsonObject parseObject(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public static JsonArray parseArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }
}
