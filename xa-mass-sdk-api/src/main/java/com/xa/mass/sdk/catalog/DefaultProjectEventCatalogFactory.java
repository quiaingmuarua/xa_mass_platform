package com.xa.mass.sdk.catalog;

import java.util.List;

/**
 * Builds the default SDK v1 catalog used by the metadata APIs.
 */
public final class DefaultProjectEventCatalogFactory {

    private static final List<String> ALL_DEFAULT_EVENT_CODES = List.of(
            "crawler.fetch-page",
            "crawler.parse-result",
            "sms.acquire-number",
            "sms.wait-code",
            "chatbot.reply",
            "chatbot.session-message"
    );

    private DefaultProjectEventCatalogFactory() {
    }

    public static ProjectEventCatalogRegistry createDefaultRegistry() {
        ProjectEventCatalogRegistry registry = new ProjectEventCatalogRegistry();

        registry.registerEvent(EventMetadata.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Fetch a single page or URL seed for downstream crawling.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        registry.registerEvent(EventMetadata.builder()
                .code("crawler.parse-result")
                .name("Crawler Parse Result")
                .description("Parse crawler output into structured downstream records.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        registry.registerEvent(EventMetadata.builder()
                .code("sms.acquire-number")
                .name("SMS Acquire Number")
                .description("Acquire a phone number or resource slot before waiting for a code.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .build());
        registry.registerEvent(EventMetadata.builder()
                .code("sms.wait-code")
                .name("SMS Wait Code")
                .description("Wait for and collect an SMS verification code.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        registry.registerEvent(EventMetadata.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Generate a chatbot response for a prompt or message bundle.")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        registry.registerEvent(EventMetadata.builder()
                .code("chatbot.session-message")
                .name("Chatbot Session Message")
                .description("Handle a session-scoped chatbot message inside a streaming conversation.")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.STREAMING))
                .build());

        registry.registerProject(project("demoApp", "演示应用",
                "Default demo project used by the validation shell.", ALL_DEFAULT_EVENT_CODES));
        registry.registerProject(project("testApp", "testApp",
                "Test project used by fixtures and local regression coverage.", ALL_DEFAULT_EVENT_CODES));
        registry.registerProject(project("crawlerApp", "Crawler",
                "Crawler-oriented project defaults for pull and streaming worker scenarios.", List.of(
                        "crawler.fetch-page",
                        "crawler.parse-result"
                )));
        registry.registerProject(project("rcsApp", "GoogleRcs",
                "RCS-oriented messaging project defaults.", List.of(
                        "sms.acquire-number",
                        "sms.wait-code",
                        "chatbot.reply",
                        "chatbot.session-message"
                )));
        registry.registerProject(project("telegramApp", "Telegram",
                "Telegram-oriented messaging project defaults.", List.of(
                        "sms.wait-code",
                        "chatbot.reply",
                        "chatbot.session-message"
                )));

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
