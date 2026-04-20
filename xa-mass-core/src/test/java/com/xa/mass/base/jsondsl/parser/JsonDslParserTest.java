package com.xa.mass.base.jsondsl.parser;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDslParserTest {

    @Test
    void shouldParseAgentFriendlyAliasesIntoCanonicalModel() {
        JsonDslDefinition definition = JsonDslParser.parse("""
                {
                  "unique_id": "agent-friendly-filter",
                  "type": "FILTER",
                  "desc": "Filter active adults",
                  "field_dsl": {
                    "status": {"$EXPR": "status == 'active'"}
                  },
                  "combineDsl": {
                    "adultCheck": {"$EXPR": "age >= 18"}
                  },
                  "cache_expire_seconds": 60,
                  "context": {
                    "MODEL": "java.util.HashMap",
                    "COUNT": 2,
                    "scope_name": "workerScope",
                    "strict": true
                  }
                }
                """);

        assertEquals("agent-friendly-filter", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.FILTER, definition.getType());
        assertEquals("Filter active adults", definition.getDescription());
        assertEquals(1, definition.getFieldDsl().size());
        assertEquals(1, definition.getCombineDsl().size());
        assertEquals(60, definition.getCacheExpireSeconds());
        assertEquals("java.util.HashMap", definition.getContext().getModel());
        assertEquals(2, definition.getContext().getCount());
        assertEquals("workerScope", definition.getContext().getScopeName());
        assertTrue(definition.getContext().getStrict());
    }

    @Test
    void shouldAllowCombineOnlyFilterDslFromJson() {
        JsonDslDefinition definition = JsonDslParser.parse("""
                {
                  "uniqueId": "combine-only-filter",
                  "type": "filter",
                  "combine_dsl": {
                    "routeCheck": {"$EXPR": "country == 'us' || channel == 'us'"}
                  }
                }
                """);

        assertEquals("combine-only-filter", definition.getUniqueId());
        assertTrue(definition.hasFieldOrCombineDsl());
        assertEquals(1, definition.getCombineDsl().size());
    }

    @Test
    void shouldRejectConflictingAliasValues() {
        JsonDslException exception = assertThrows(JsonDslException.class, () -> JsonDslParser.parse("""
                {
                  "uniqueId": "one-id",
                  "unique_id": "another-id",
                  "type": "generate",
                  "context": {
                    "model": "java.util.HashMap"
                  }
                }
                """));

        assertTrue(exception.getMessage().contains("Conflicting alias values"));
    }
}
