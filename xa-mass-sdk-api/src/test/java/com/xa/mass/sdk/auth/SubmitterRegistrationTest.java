package com.xa.mass.sdk.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class SubmitterRegistrationTest {

    @Test
    void builderRejectsBlankPrincipalId() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SubmitterRegistration.builder()
                        .principalId(" ")
                        .credential("api-key")
                        .build());
    }

    @Test
    void builderRejectsBlankCredential() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SubmitterRegistration.builder()
                        .principalId("telegram-bot")
                        .credential(" ")
                        .build());
    }

    @Test
    void builderNormalizesOptionalFieldsAndOmitsInvalidAttributes() {
        SubmitterRegistration registration = SubmitterRegistration.builder()
                .principalId(" telegram-bot ")
                .credential(" dev-api-key ")
                .userId(" bot-user ")
                .projectScope(" telegramApp ")
                .attributes(Map.of(
                        " channel ", "telegram",
                        " ", "ignored"
                ))
                .build();

        Assertions.assertEquals("telegram-bot", registration.getPrincipalId());
        Assertions.assertEquals("dev-api-key", registration.getCredential());
        Assertions.assertEquals("dev-api-...", registration.getKeyPrefix());
        Assertions.assertEquals("bot-user", registration.getUserId());
        Assertions.assertEquals("telegramApp", registration.getProjectScope());
        Assertions.assertEquals(List.of(PrincipalContext.TASK_CREATE_PERMISSION), registration.getPermissions());
        Assertions.assertEquals(List.of("telegramApp"), registration.getProjectScopes());
        Assertions.assertEquals(List.of(), registration.getEventScopes());
        Assertions.assertEquals(Map.of("channel", "telegram"), registration.getAttributes());
    }

    @Test
    void builderSupportsPerCredentialPermissionsAndScopes() {
        SubmitterRegistration registration = SubmitterRegistration.builder()
                .principalId(" crawler-key ")
                .credential(" mass_sk_test_123456 ")
                .keyPrefix(" mass_sk_test ")
                .userId(" crawler-user ")
                .permissions(List.of(" task:create ", "metadata:view", "task:create"))
                .projectScopes(List.of(" crawlerApp ", "demoApp", "crawlerApp"))
                .eventScopes(List.of(" crawler.fetch-page ", "tool.country.capital.lookup"))
                .build();

        Assertions.assertEquals("crawler-key", registration.getPrincipalId());
        Assertions.assertEquals("mass_sk_test", registration.getKeyPrefix());
        Assertions.assertEquals(List.of("task:create", "metadata:view"), registration.getPermissions());
        Assertions.assertEquals(List.of("crawlerApp", "demoApp"), registration.getProjectScopes());
        Assertions.assertEquals(List.of("crawler.fetch-page", "tool.country.capital.lookup"), registration.getEventScopes());

        PrincipalContext context = registration.toPrincipalContext();
        Assertions.assertTrue(context.hasPermission("task:create"));
        Assertions.assertTrue(context.allowsProject("crawlerApp"));
        Assertions.assertFalse(context.allowsProject("otherApp"));
        Assertions.assertTrue(context.allowsEvent("crawler.fetch-page"));
        Assertions.assertFalse(context.allowsEvent("chatbot.reply"));
    }

    @Test
    void toStringDoesNotLeakCredential() {
        SubmitterRegistration registration = SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("secret-key")
                .build();

        Assertions.assertFalse(registration.toString().contains("secret-key"));
    }

    @Test
    void metadataProjectionDoesNotExposeCredential() {
        SubmitterRegistration registration = SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("secret-key")
                .userId("bot-user")
                .projectScope("telegramApp")
                .attributes(Map.of("channel", "telegram"))
                .build();

        SubmitterMetadata metadata = registration.toMetadata();

        Assertions.assertEquals("telegram-bot", metadata.getPrincipalId());
        Assertions.assertEquals("bot-user", metadata.getUserId());
        Assertions.assertEquals("telegramApp", metadata.getProjectScope());
        Assertions.assertEquals(List.of(PrincipalContext.TASK_CREATE_PERMISSION), metadata.getPermissions());
        Assertions.assertEquals(List.of("telegramApp"), metadata.getProjectScopes());
        Assertions.assertEquals(Map.of("channel", "telegram"), metadata.getAttributes());
        Assertions.assertFalse(metadata.toString().contains("secret-key"));
    }
}
