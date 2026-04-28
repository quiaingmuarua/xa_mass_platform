package com.xa.mass.base.jsondsl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.DslKeyword;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.generate.DslObjectBuilder;
import com.xa.mass.base.jsondsl.processor.FilterProcessor;
import com.xa.mass.base.jsondsl.processor.FilterResult;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Legacy JSON-DSL entry for mock data generation and compatibility filters.
 *
 * <p>The active worker matching path does not use this class. Matching rules
 * are evaluated through engine {@code RuleDefinition} and QLExpress over
 * {@code WorkerMatchContext}. Keep this class isolated to mock/dev fixtures
 * and legacy JSON-DSL compatibility. Do not route canonical typed DSL parsing
 * through this class.
 *
 * @deprecated Prefer the typed {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition}
 * model and {@link com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine}
 * for generic JSON-DSL processing. Use engine rule DSL for worker matching.
 */
@Deprecated(since = "2.0.0", forRemoval = false)
public class JsonDslEngine {
    private static final Gson gson = GsonConfig.buildGson();

    /**
     * Generates objects from the legacy mock-data JSON-DSL format.
     *
     * @deprecated This method is retained for mock-data compatibility only.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    @SuppressWarnings("unchecked")
    public static <T> List<T> generateList(String jsonDsl, Class<T> targetType) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();

        if (hasMultipleModels(root)) {
            throw new JsonDslException("generateList only supports one root model");
        }

        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
        String modelName = root.has(DslKeyword.MODEL.name()) ? root.get(DslKeyword.MODEL.name()).getAsString() : "Root";

        List<T> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DslContext context = new DslContext();
            context.setScopeName(modelName);
            context.setVariable("&" + modelName + ".index", i);
            T obj = DslObjectBuilder.mockFromDsl(root, context, targetType);
            result.add(obj);
        }

        return result;
    }

    public static <T> List<T> filter(List<T> data, String jsonDsl) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        com.xa.mass.base.jsondsl.model.JsonDslDefinition filterDef =
                com.xa.mass.base.jsondsl.parser.JsonDslParser.parse(jsonDsl);
        filterDef.validate();
        FilterProcessor filterProcessor = ProcessorRegistry.getFilterProcessor();
        FilterResult<T> result =
                filterProcessor.filterList(data, filterDef, new ProcessingContext("JsonDslEngine.filter"));
        return result.getPassed();
    }

    private static boolean hasMultipleModels(JsonObject root) {
        int modelCount = 0;
        for (String key : root.keySet()) {
            if (key.equals(DslKeyword.MODEL.name())) {
                modelCount++;
            }
        }

        if (modelCount > 0) {
            return false;
        }

        int subModelCount = 0;
        for (String key : root.keySet()) {
            com.google.gson.JsonElement value = root.get(key);
            if (value.isJsonObject()) {
                JsonObject subObj = value.getAsJsonObject();
                if (subObj.has(DslKeyword.MODEL.name())) {
                    subModelCount++;
                }
            }
        }

        return subModelCount > 1;
    }
}
