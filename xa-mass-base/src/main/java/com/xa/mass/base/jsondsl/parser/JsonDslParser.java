package com.xa.mass.base.jsondsl.parser;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.Map;
import java.util.Set;

/**
 * Parser for the canonical typed JSON DSL.
 *
 * <p>This parser keeps the typed processor contract intentionally narrow. It
 * accepts the canonical camelCase shape and a small snake_case compatibility
 * surface, while rejecting legacy/mock fields that belong to the compatibility
 * path instead of the typed runtime path.
 */
public class JsonDslParser {

    private static final Gson GSON = GsonConfig.buildGson();

    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of(
            "uniqueId", "unique_id",
            "type",
            "priority",
            "description",
            "version",
            "createTime", "create_time",
            "updateTime", "update_time",
            "context",
            "fieldDsl", "field_dsl",
            "combineDsl", "combine_dsl",
            "extensions",
            "tags",
            "author",
            "enabled"
    );

    private static final Set<String> ALLOWED_CONTEXT_KEYS = Set.of(
            "model",
            "count",
            "scopeName", "scope_name",
            "parameters",
            "strict"
    );

    public static JsonDslDefinition parse(String jsonDsl) {
        try {
            JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
            return parseStandardDsl(root);
        } catch (JsonDslException e) {
            throw e;
        } catch (Exception e) {
            throw new JsonDslException("Failed to parse DSL: " + e.getMessage(), e);
        }
    }

    private static JsonDslDefinition parseStandardDsl(JsonObject root) {
        rejectUnsupportedRootFields(root);
        rejectUnknownFields(root, ALLOWED_ROOT_KEYS, "root");

        JsonDslDefinition definition = new JsonDslDefinition();
        definition.setUniqueId(readAliasedString(root, "uniqueId", "unique_id"));
        definition.setType(JsonDslDefinition.DslType.fromCode(readAliasedString(root, "type")));
        definition.setPriority(readAliasedInteger(root, 1, "priority"));
        definition.setDescription(readAliasedString(root, "description"));

        String version = readAliasedString(root, "version");
        if (version != null) {
            definition.setVersion(version);
        }

        Long createTime = readAliasedLong(root, "createTime", "create_time");
        if (createTime != null) {
            definition.setCreateTime(createTime);
        }

        Long updateTime = readAliasedLong(root, "updateTime", "update_time");
        if (updateTime != null) {
            definition.setUpdateTime(updateTime);
        }

        JsonObject contextObject = readAliasedObject(root, "context");
        if (contextObject != null) {
            definition.setContext(parseContext(contextObject));
        }

        definition.setFieldDsl(readAliasedMap(root, "fieldDsl", "field_dsl"));
        definition.setCombineDsl(readAliasedMap(root, "combineDsl", "combine_dsl"));
        definition.setExtensions(readAliasedMap(root, "extensions"));

        String[] tags = readAliasedArray(root, "tags");
        if (tags != null) {
            definition.setTags(tags);
        }

        String author = readAliasedString(root, "author");
        if (author != null) {
            definition.setAuthor(author);
        }

        Boolean enabled = readAliasedBoolean(root, "enabled");
        if (enabled != null) {
            definition.setEnabled(enabled);
        }

        definition.validate();
        return definition;
    }

    private static JsonDslContext parseContext(JsonObject contextObj) {
        rejectUnsupportedContextFields(contextObj);
        rejectUnknownFields(contextObj, ALLOWED_CONTEXT_KEYS, "context");

        JsonDslContext context = new JsonDslContext();
        context.setModel(readAliasedString(contextObj, "model"));

        Integer count = readAliasedInteger(contextObj, null, "count");
        if (count != null) {
            context.setCount(count);
        }

        context.setScopeName(readAliasedString(contextObj, "scopeName", "scope_name"));
        context.setParameters(readAliasedMap(contextObj, "parameters"));

        Boolean strict = readAliasedBoolean(contextObj, "strict");
        if (strict != null) {
            context.setStrict(strict);
        }

        context.validate();
        return context;
    }

    public static String toJson(JsonDslDefinition definition) {
        return GSON.toJson(definition);
    }

    private static void rejectUnsupportedRootFields(JsonObject root) {
        rejectIfPresent(root, "desc", "unsupported legacy alias 'desc'; use 'description'");
        rejectIfPresent(root, "fields", "unsupported legacy field 'fields'; use 'fieldDsl'");
        rejectIfPresent(root, "cacheable", "unsupported typed field 'cacheable'; expression/runtime caching is not a typed DSL contract");
        rejectIfPresent(root, "cacheExpireSeconds", "unsupported typed field 'cacheExpireSeconds'; expression/runtime caching is not a typed DSL contract");
        rejectIfPresent(root, "cache_expire_seconds", "unsupported legacy alias 'cache_expire_seconds'; expression/runtime caching is not a typed DSL contract");
        rejectIfPresent(root, "FIELDS", "legacy/mock field 'FIELDS' is not supported by the typed parser; use JsonDslEngine or legacy/mock generation path");
        rejectIfPresent(root, "MODEL", "legacy/mock field 'MODEL' is not supported by the typed parser; use context.model or the legacy/mock path");
        rejectIfPresent(root, "COUNT", "legacy/mock field 'COUNT' is not supported by the typed parser; use context.count or the legacy/mock path");
    }

    private static void rejectUnsupportedContextFields(JsonObject contextObj) {
        rejectIfPresent(contextObj, "MODEL", "unsupported legacy context field 'MODEL'; use 'model'");
        rejectIfPresent(contextObj, "COUNT", "unsupported legacy context field 'COUNT'; use 'count'");
        rejectIfPresent(contextObj, "TYPE", "unsupported legacy context field 'TYPE'; typed context.type is not part of the canonical contract");
        rejectIfPresent(contextObj, "type", "unsupported typed context field 'type'; it is not consumed by the typed runtime");
        rejectIfPresent(contextObj, "parentScope", "unsupported typed context field 'parentScope'; it is not consumed by the typed runtime");
        rejectIfPresent(contextObj, "parent_scope", "unsupported legacy alias 'parent_scope'; parent scope is not part of the canonical typed contract");
        rejectIfPresent(contextObj, "debug", "unsupported typed context field 'debug'; use ProcessingContext.debug instead");
        rejectIfPresent(contextObj, "DEBUG", "unsupported legacy context field 'DEBUG'; use ProcessingContext.debug instead");
    }

    private static void rejectUnknownFields(JsonObject obj, Set<String> allowedKeys, String path) {
        for (String key : obj.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new JsonDslException("Unknown " + path + " field: " + key);
            }
        }
    }

    private static void rejectIfPresent(JsonObject obj, String key, String message) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            throw new JsonDslException(message);
        }
    }

    private static String readAliasedString(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : element.getAsString();
    }

    private static Integer readAliasedInteger(JsonObject obj, Integer defaultValue, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? defaultValue : element.getAsInt();
    }

    private static Long readAliasedLong(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : element.getAsLong();
    }

    private static Boolean readAliasedBoolean(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : element.getAsBoolean();
    }

    private static JsonObject readAliasedObject(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : element.getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readAliasedMap(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : GSON.fromJson(element, Map.class);
    }

    private static String[] readAliasedArray(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : GSON.fromJson(element, String[].class);
    }

    private static JsonElement readAliasedElement(JsonObject obj, String... keys) {
        JsonElement chosen = null;
        String chosenKey = null;
        String chosenJson = null;

        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull()) {
                continue;
            }
            JsonElement current = obj.get(key);
            String currentJson = GSON.toJson(current);
            if (chosen == null) {
                chosen = current;
                chosenKey = key;
                chosenJson = currentJson;
                continue;
            }
            if (!chosenJson.equals(currentJson)) {
                throw new JsonDslException("Conflicting alias values for '" + chosenKey + "' and '" + key + "'");
            }
        }

        return chosen;
    }
}
