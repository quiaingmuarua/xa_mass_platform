package com.xa.mass.base.jsondsl.parser;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDslParserTest {

    @Test
    void shouldParseCanonicalCamelCaseTypedDsl() {
        JsonDslDefinition definition = JsonDslParser.parse("""
                {
                  "uniqueId": "canonical-filter",
                  "type": "FILTER",
                  "description": "Filter active adults",
                  "fieldDsl": {
                    "status": {"$EXPR": "status == 'active'"}
                  },
                  "combineDsl": {
                    "adultCheck": {"$EXPR": "age >= 18"}
                  },
                  "context": {
                    "model": "java.util.HashMap",
                    "count": 2,
                    "scopeName": "workerScope",
                    "strict": true
                  }
                }
                """);

        assertEquals("canonical-filter", definition.getUniqueId());
        assertEquals(JsonDslDefinition.DslType.FILTER, definition.getType());
        assertEquals("Filter active adults", definition.getDescription());
        assertEquals(1, definition.getFieldDsl().size());
        assertEquals(1, definition.getCombineDsl().size());
        assertEquals("java.util.HashMap", definition.getContext().getModel());
        assertEquals(2, definition.getContext().getCount());
        assertEquals("workerScope", definition.getContext().getScopeName());
        assertTrue(definition.getContext().getStrict());
    }

    @Test
    void shouldParseSupportedSnakeCaseAliases() {
        JsonDslDefinition definition = JsonDslParser.parse("""
                {
                  "unique_id": "agent-friendly-filter",
                  "type": "filter",
                  "field_dsl": {
                    "status": {"$EXPR": "status == 'active'"}
                  },
                  "combine_dsl": {
                    "adultCheck": {"$EXPR": "age >= 18"}
                  },
                  "context": {
                    "model": "java.util.HashMap",
                    "count": 2,
                    "scope_name": "workerScope",
                    "strict": true
                  }
                }
                """);

        assertEquals("agent-friendly-filter", definition.getUniqueId());
        assertTrue(definition.hasFieldOrCombineDsl());
        assertEquals("workerScope", definition.getContext().getScopeName());
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

    @Test
    void shouldRejectRetiredTypedFields() {
        JsonDslException exception = assertThrows(JsonDslException.class, () -> JsonDslParser.parse("""
                {
                  "uniqueId": "legacy-cache",
                  "type": "filter",
                  "cacheable": true,
                  "fieldDsl": {
                    "status": {"$EXPR": "status == 'active'"}
                  }
                }
                """));

        assertTrue(exception.getMessage().contains("cacheable"));
    }

    @Test
    void shouldRejectStoppedLegacyAliases() {
        JsonDslException exception = assertThrows(JsonDslException.class, () -> JsonDslParser.parse("""
                {
                  "uniqueId": "legacy-desc",
                  "type": "filter",
                  "desc": "legacy description",
                  "fieldDsl": {
                    "status": {"$EXPR": "status == 'active'"}
                  }
                }
                """));

        assertTrue(exception.getMessage().contains("desc"));
    }

    @Test
    void shouldRejectLegacyMockShapeInTypedParser() {
        JsonDslException exception = assertThrows(JsonDslException.class, () -> JsonDslParser.parse("""
                {
                  "MODEL": "java.util.HashMap",
                  "COUNT": 1,
                  "FIELDS": {
                    "name": "legacy"
                  }
                }
                """));

        assertTrue(exception.getMessage().contains("legacy/mock"));
    }

    @Test
    void shouldRejectUnsupportedContextFields() {
        JsonDslException exception = assertThrows(JsonDslException.class, () -> JsonDslParser.parse("""
                {
                  "uniqueId": "legacy-context",
                  "type": "generate",
                  "context": {
                    "model": "java.util.HashMap",
                    "count": 1,
                    "debug": true
                  },
                  "fieldDsl": {
                    "name": "legacy"
                  }
                }
                """));

        assertTrue(exception.getMessage().contains("debug"));
    }
}
