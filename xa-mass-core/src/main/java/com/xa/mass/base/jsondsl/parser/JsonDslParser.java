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

/**
 * Parser for the standardized JSON DSL.
 *
 * <p>This parser intentionally accepts a few agent-friendly aliases such as
 * snake_case and camelCase variants, but it normalizes them into the canonical
 * {@link JsonDslDefinition} model and validates conflicts early.
 */
public class JsonDslParser {

    private static final Gson gson = GsonConfig.buildGson();

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
        JsonDslDefinition definition = new JsonDslDefinition();

        definition.setUniqueId(readAliasedString(root, "uniqueId", "unique_id"));
        definition.setType(JsonDslDefinition.DslType.fromCode(readAliasedString(root, "type")));
        definition.setPriority(readAliasedInteger(root, 1, "priority"));
        definition.setDescription(readAliasedString(root, "description", "desc"));
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

        Map<String, Object> fieldDsl = readAliasedMap(root, "fieldDsl", "field_dsl");
        if (fieldDsl == null) {
            fieldDsl = readAliasedMap(root, "fields");
        }
        definition.setFieldDsl(fieldDsl);

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

        Boolean cacheable = readAliasedBoolean(root, "cacheable");
        if (cacheable != null) {
            definition.setCacheable(cacheable);
        }

        Integer cacheExpireSeconds = readAliasedInteger(root, null, "cacheExpireSeconds", "cache_expire_seconds");
        if (cacheExpireSeconds != null) {
            definition.setCacheExpireSeconds(cacheExpireSeconds);
        }

        definition.validate();
        return definition;
    }

    private static JsonDslContext parseContext(JsonObject contextObj) {
        JsonDslContext context = new JsonDslContext();

        context.setModel(readAliasedString(contextObj, "MODEL", "model"));

        Integer count = readAliasedInteger(contextObj, null, "COUNT", "count");
        if (count != null) {
            context.setCount(count);
        }

        String type = readAliasedString(contextObj, "TYPE", "type");
        if (type != null) {
            context.setType(type);
        }

        context.setScopeName(readAliasedString(contextObj, "scopeName", "scope_name"));
        context.setParentScope(readAliasedString(contextObj, "parentScope", "parent_scope"));
        context.setParameters(readAliasedMap(contextObj, "parameters"));

        Boolean debug = readAliasedBoolean(contextObj, "debug");
        if (debug != null) {
            context.setDebug(debug);
        }

        Boolean strict = readAliasedBoolean(contextObj, "strict");
        if (strict != null) {
            context.setStrict(strict);
        }

        context.validate();
        return context;
    }

    public static String toJson(JsonDslDefinition definition) {
        return gson.toJson(definition);
    }

    private static String readAliasedString(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : element.getAsString();
    }

    private static Integer readAliasedInteger(JsonObject obj, Integer defaultValue, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        if (element == null) {
            return defaultValue;
        }
        return element.getAsInt();
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
        return element == null ? null : gson.fromJson(element, Map.class);
    }

    private static String[] readAliasedArray(JsonObject obj, String... keys) {
        JsonElement element = readAliasedElement(obj, keys);
        return element == null ? null : gson.fromJson(element, String[].class);
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
            String currentJson = gson.toJson(current);
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
