package com.xa.mass.sdk.catalog;

import java.util.List;

/**
 * Builds the default SDK catalog.
 *
 * <p>This factory only registers baseline project identities. Business-facing
 * task events are expected to be registered explicitly by the embedding
 * runtime. Example/dev scenario events belong in dev fixtures, not in the
 * library defaults.
 */
public final class DefaultProjectEventCatalogFactory {

    private DefaultProjectEventCatalogFactory() {
    }

    public static ProjectEventCatalogRegistry createDefaultRegistry() {
        ProjectEventCatalogRegistry registry = new ProjectEventCatalogRegistry();

        registry.registerProject(project("demoApp", "Demo App",
                "Default demo project identity used by the validation shell.", List.of()));
        registry.registerProject(project("testApp", "Test App",
                "Test project identity used by fixtures and regression coverage.", List.of()));
        registry.registerProject(project("rcsApp", "GoogleRcs",
                "RCS-oriented project identity placeholder.", List.of()));
        registry.registerProject(project("telegramApp", "Telegram",
                "Telegram-oriented project identity placeholder.", List.of()));

        return registry;
    }

    private static ProjectMetadata project(String code, String name, String description, List<String> eventCodes) {
        return ProjectMetadata.builder()
                .code(code)
                .name(name)
                .description(description)
                .eventCodes(eventCodes)
                .build();
    }
}
