package com.xa.mass.sdk.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
        Assertions.assertEquals("bot-user", registration.getUserId());
        Assertions.assertEquals("telegramApp", registration.getProjectScope());
        Assertions.assertEquals(Map.of("channel", "telegram"), registration.getAttributes());
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
        Assertions.assertEquals(Map.of("channel", "telegram"), metadata.getAttributes());
        Assertions.assertFalse(metadata.toString().contains("secret-key"));
    }
}
