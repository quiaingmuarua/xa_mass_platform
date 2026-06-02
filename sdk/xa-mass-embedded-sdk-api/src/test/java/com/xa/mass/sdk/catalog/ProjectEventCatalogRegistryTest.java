package com.xa.mass.sdk.catalog;

import com.xa.mass.sdk.event.EventDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectEventCatalogRegistryTest {

    @Test
    void defaultRegistryLoadsBaselineProjectsWithoutExampleEvents() {
        ProjectEventCatalogRegistry registry = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();

        List<ProjectDefinition> projects = registry.listProjects();
        List<EventDefinition> events = registry.listEvents();

        assertFalse(projects.isEmpty());
        assertTrue(events.isEmpty());
        assertTrue(projects.stream().anyMatch(project -> "demoApp".equals(project.getCode())));
        assertTrue(projects.stream().allMatch(project -> project.getEventCodes().isEmpty()));
    }

    @Test
    void registrySupportsManyToManyProjectEventBinding() {
        ProjectEventCatalogRegistry registry = new ProjectEventCatalogRegistry();
        EventDefinition sharedEvent = EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Shared chatbot event")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build();
        registry.registerEventDefinition(sharedEvent);
        registry.registerProject(ProjectDefinition.builder()
                .code("alpha")
                .name("Alpha")
                .description("alpha project")
                .eventCodes(List.of("chatbot.reply"))
                .build());
        registry.registerProject(ProjectDefinition.builder()
                .code("beta")
                .name("Beta")
                .description("beta project")
                .eventCodes(List.of("chatbot.reply"))
                .build());

        assertEquals(1, registry.getEventsForProject("alpha").size());
        assertEquals(1, registry.getEventsForProject("beta").size());
        assertEquals("chatbot.reply", registry.getEventsForProject("alpha").get(0).getCode());
    }

    @Test
    void disabledMetadataRemainsVisible() {
        ProjectEventCatalogRegistry registry = new ProjectEventCatalogRegistry();
        registry.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("disabled event")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.STREAMING))
                .enabled(false)
                .build());
        registry.registerProject(ProjectDefinition.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("disabled project")
                .eventCodes(List.of("crawler.fetch-page"))
                .enabled(false)
                .build());

        ProjectDefinition project = registry.getProject("crawlerApp");
        EventDefinition event = registry.getEvent("crawler.fetch-page");

        assertNotNull(project);
        assertNotNull(event);
        assertFalse(project.isEnabled());
        assertFalse(event.isEnabled());
    }

    @Test
    void unknownProjectOrEventReturnsNullOrEmpty() {
        ProjectEventCatalogRegistry registry = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();

        assertNull(registry.getProject("missing-project"));
        assertNull(registry.getEvent("missing-event"));
        assertTrue(registry.getEventsForProject("missing-project").isEmpty());
    }
}
