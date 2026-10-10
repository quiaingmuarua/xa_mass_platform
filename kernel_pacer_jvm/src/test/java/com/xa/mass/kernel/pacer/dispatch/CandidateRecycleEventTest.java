package com.xa.mass.kernel.pacer.dispatch;

import java.nio.file.Path;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CandidateRecycleEventTest {
    @TempDir Path directory;
    @Test void nameEnabledRecordingContainsActualHintGaugesAndCounts() throws Exception {
        var hints = new CandidateRecycleHints(() -> 0);
        hints.offer("g", Map.of("w", 1L), List.of("w"));
        var path = directory.resolve("enabled.jfr");
        try (var recording = new Recording()) {
            recording.enable("xa.mass.CandidateRecycle"); recording.start();
            hints.observe(); recording.stop(); recording.dump(path);
        }
        var events = RecordingFile.readAllEvents(path).stream()
                .filter(event -> event.getEventType().getName().equals("xa.mass.CandidateRecycle")).toList();
        assertEquals(1, events.size());
        assertEquals(1, events.getFirst().getInt("pending"));
        assertEquals(1L, events.getFirst().getLong("accepted"));
        assertEquals(0L, events.getFirst().getLong("dropped"));
    }
    @Test void recordingWithoutExplicitEnablingHasNoHintEvents() throws Exception {
        var path = directory.resolve("disabled.jfr");
        try (var recording = new Recording()) {
            recording.start(); new CandidateRecycleHints(() -> 0).observe();
            recording.stop(); recording.dump(path);
        }
        assertTrue(RecordingFile.readAllEvents(path).stream()
                .noneMatch(event -> event.getEventType().getName().equals("xa.mass.CandidateRecycle")));
    }
}
