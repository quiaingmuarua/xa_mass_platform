package com.xa.mass.base.project;

import com.xa.mass.base.model.ProjectRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRegistryTest {

    @Test
    void defaultRegistryIncludesBuiltInProjects() {
        assertTrue(ProjectRegistry.isValidCode("demoApp"));
        assertTrue(ProjectRegistry.isValidCode("crawlerApp"));
        assertTrue(ProjectRegistry.listProjectCodes().contains("telegramApp"));
    }

    @Test
    void projectRefResolvesCustomRegisteredProject() {
        ProjectRegistry.register("botAppRegistryTest", "Bot App Registry Test", true);

        ProjectRef projectRef = ProjectRef.require("botAppRegistryTest");

        assertEquals("botAppRegistryTest", projectRef.getCode());
        assertEquals("Bot App Registry Test", projectRef.getName());
    }

    @Test
    void disabledProjectIsVisibleButNotExecutable() {
        ProjectRegistry.register("disabledRegistryTest", "Disabled Registry Test", false);

        assertFalse(ProjectRegistry.isValidCode("disabledRegistryTest"));
        assertThrows(IllegalArgumentException.class, () -> ProjectRef.require("disabledRegistryTest"));
    }
}
