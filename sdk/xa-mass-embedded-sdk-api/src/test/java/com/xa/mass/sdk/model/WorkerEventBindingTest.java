package com.xa.mass.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerEventBindingTest {

    @Test
    void normalizesProjectCodes() {
        WorkerEventBinding binding = WorkerEventBinding.builder()
                .eventCode(" crawler.fetch-page ")
                .projectCodes(List.of(" demoApp ", "demoApp", " ", "crawlerApp"))
                .build();

        assertEquals("crawler.fetch-page", binding.getEventCode());
        assertEquals(List.of("demoApp", "crawlerApp"), binding.getProjectCodes());
    }

    @Test
    void rejectsBlankEventCode() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkerEventBinding.builder().eventCode(" ").build());
    }
}
