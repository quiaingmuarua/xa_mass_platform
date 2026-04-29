package com.xa.mass.engine.rules;

import com.google.gson.Gson;
import com.xa.mass.storage.rule.RuleDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleDefinitionCompatibilityTest {

    private final Gson gson = new Gson();

    @Test
    void canonicalFieldsShouldDriveLegacyAliases() {
        RuleDefinition rule = new RuleDefinition();
        rule.setContent("supportsProject == true");
        rule.setDescription("Worker must support the task project");

        assertEquals("supportsProject == true", rule.getExpression());
        assertEquals("Worker must support the task project", rule.getDesc());
    }

    @Test
    void legacyJsonAliasesShouldStillResolveThroughCanonicalGetters() {
        RuleDefinition rule = gson.fromJson("""
                {
                  "expression": "hasWorkerContext == false",
                  "desc": "Legacy alias only"
                }
                """, RuleDefinition.class);

        assertEquals("hasWorkerContext == false", rule.getContent());
        assertEquals("Legacy alias only", rule.getDescription());
    }
}
