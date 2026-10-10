package com.xa.mass.workersimulator.messaging;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MessageLabTest {
    private static MessageLab lab(long seed, java.util.function.LongSupplier clock, java.util.function.Predicate<MessageLab.Receipt> callback) {
        return new MessageLab(seed, MessageSettings.defaults(), clock, () -> clock.getAsLong() * 1_000_000L, callback);
    }
    private static final Map<String,Object> SENDER = Map.of("workerGroupId", "g", "replicaKey", "r", "workerId", "w", "phone", "+86123", "country", "CN");

    @Test void instructionValidationIsStrictAndDropAffectsOnlyTheFinalDeclaredStep() {
        var plan = MessageInstructions.parse("{\"receipts_status\":[\"read\",\"replied\",\"replied\"],\"delayMs\":7,\"probability\":1,\"text\":\"reply\"}", 1, "m");
        assertThat(plan.requested()).containsExactly("read", "replied", "replied");
        assertThat(plan.retained()).containsExactly("read", "replied"); assertThat(plan.delays()).containsExactly(7, 7);
        assertThat(plan.droppedLast()).isTrue();
        assertThat(MessageInstructions.parse("{}", 1, "m").retained()).isEmpty();
        for (String invalid : List.of("text", "[]", "{\"unknown\":1}", "{\"delayMs\":1,\"delayMs\":2}",
                "{\"receipts_status\":[\"delivered\"]}", "{\"receipts_status\":[\"read\",\"read\"]}",
                "{\"receipts_status\":[\"replied\",\"read\"],\"text\":\"r\"}", "{\"receipts_status\":[\"replied\"]}",
                "{\"delayMs\":[2,1]}", "{\"delayMs\":0.1}", "{\"delayMs\":60001}", "{\"probability\":1.1}",
                "{\"probability\":\"0.5\"}", "{\"text\":null}")) {
            assertThatThrownBy(() -> MessageInstructions.parse(invalid, 1, "m")).as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
        String random = "{\"receipts_status\":[\"read\",\"replied\"],\"probability\":0.5,\"text\":\"r\"}";
        assertThat(MessageInstructions.parse(random, 42, "m")).isEqualTo(MessageInstructions.parse(random, 42, "m"));
    }

    @Test void oneNextActionPerMessageUsesBusinessClockAndDuplicateAcceptanceNeverResamples() {
        var now = new AtomicLong(1000); var receipts = new ArrayList<MessageLab.Receipt>();
        try (var lab = lab(42, now::get, receipt -> { receipts.add(receipt); return false; })) {
            var request = MessageScenarioTest.send("m", "{\"receipts_status\":[\"read\",\"replied\"],\"delayMs\":1000,\"text\":\"r\"}");
            var first = lab.accept(request, SENDER, "callback", MessageContentMode.LAB_JSON);
            assertThat(receipts).hasSize(1); assertThat(receipts.getFirst().snapshot()).containsEntry("status", "DELIVERED");
            assertThat(lab.accept(request, SENDER, "other", MessageContentMode.LAB_JSON)).isEqualTo(first);
            now.set(1999); lab.tick(); assertThat(receipts).hasSize(1);
            now.set(2500); lab.tick(); assertThat(receipts).hasSize(2);
            now.set(3499); lab.tick(); assertThat(receipts).hasSize(2);
            now.set(3500); lab.tick(); assertThat(receipts).hasSize(3);
            assertThat(lab.metrics()).containsEntry("scheduled", 0).containsEntry("callbackFailed", 3L);
            assertThat(receipts.get(2).snapshot()).containsEntry("observedAtMillis", 3500L);
        }
    }

    @Test void deliveredIsUnconditionalEvenWhenTheOnlyLaterStepIsDropped() {
        var receipts = new ArrayList<MessageLab.Receipt>();
        try (var lab = lab(1, () -> 1000L, r -> { receipts.add(r); return true; })) {
            lab.accept(MessageScenarioTest.send("m", "{\"receipts_status\":[\"read\"],\"probability\":1,\"delayMs\":0}"), SENDER, "c", MessageContentMode.LAB_JSON);
            lab.tick(); assertThat(receipts).hasSize(1); assertThat(lab.metrics()).containsEntry("scheduled", 0);
        }
    }

    @Test void fullHoldCapacityDropsObservationsWithoutRejectingSendsOrLaterActions() {
        var now = new AtomicLong(1000);
        try (var lab = lab(1, now::get, r -> { throw new AssertionError("Held callback escaped"); })) {
            lab.hold(true);
            lab.accept(MessageScenarioTest.send("m", "{\"receipts_status\":[\"replied\"],\"delayMs\":1000,\"text\":\"auto\"}"), SENDER, "c", MessageContentMode.LAB_JSON);
            for (int i = 1; i < MessageLab.MAX_HELD; i++) lab.act("g", "r", "m", "reply", Map.of("requestId", "r" + i, "text", "reply"));
            assertThat(lab.act("g", "r", "m", "reply", Map.of("requestId", "overflow", "text", "no")))
                    .containsEntry("persisted", true).containsEntry("held", false).containsEntry("callbackQueued", false);
            assertThat(lab.accept(MessageScenarioTest.send("other"), SENDER, "c2", MessageContentMode.LAB_JSON)).containsKey("snapshot");
            assertThat(lab.metrics()).containsEntry("messages", 2).containsEntry("acceptedMessages", 2L);
            now.set(2000); lab.tick();
            var row = (Map<?,?>)((List<?>)lab.page(null, null, 0, 1).get("items")).getFirst();
            assertThat((String) row.get("replyRequestId")).startsWith("auto-");
            assertThat(((Map<?,?>)row.get("plan")).get("failure")).isNull();
            assertThat((List<?>) row.get("receipts")).hasSize(64);
            assertThat(lab.metrics()).containsEntry("scheduled", 0).containsEntry("held", 10000).containsEntry("droppedReceipts", 3L);
        }
    }

    @Test void textIsOpaqueAndAcceptanceWindowDoesNotRenewOnReadOrDuplicate() {
        var wall = new AtomicLong(1000); var nanos = new AtomicLong();
        var settings = new MessageSettings(100, 2, 50, 1, 1);
        try (var lab = new MessageLab(0, settings, wall::get, nanos::get, r -> { throw new AssertionError("Text emitted a receipt"); })) {
            var request = MessageScenarioTest.send("m", "  模板 {{name}}\n{}  ");
            var first = lab.accept(request, SENDER, "original", MessageContentMode.TEXT);
            assertThat(((Map<?,?>) first.get("snapshot")).get("body")).isEqualTo(request.get("body"));
            assertThat(((Map<?,?>) first.get("snapshot")).get("status")).isEqualTo("SENT");
            var second = lab.accept(MessageScenarioTest.send("n", "{}"), SENDER, "unused", MessageContentMode.TEXT);
            assertThat(second.get("callbackId")).isNull();
            assertThat(lab.metrics()).containsEntry("trackedMessages", 1).containsEntry("trackingSkipped", 1L);
            assertThatThrownBy(() -> lab.accept(MessageScenarioTest.send("full"), SENDER, null, MessageContentMode.TEXT))
                    .isInstanceOf(MessageLab.CapacityExceeded.class);
            assertThat(lab.metrics()).containsEntry("acceptedMessages", 2L);
            nanos.set(99_000_000); wall.set(900);
            assertThat(lab.accept(request, SENDER, "replacement", MessageContentMode.TEXT)).isEqualTo(first);
            assertThat(lab.acceptances(List.of("m", "absent")).get("missingIds")).isEqualTo(List.of("absent"));
            assertThat(lab.acceptances(List.of("m")).toString()).doesNotContain("模板").doesNotContain("recipientId");
            assertThat(lab.metrics()).containsEntry("trackedMessages", 0).containsEntry("recentSentRecords", 0);
            nanos.set(100_000_000);
            assertThat(lab.acceptances(List.of("m", "n")).get("missingIds")).isEqualTo(List.of("m", "n"));
            lab.accept(MessageScenarioTest.send("full"), SENDER, null, MessageContentMode.TEXT);
            assertThat(lab.metrics()).containsEntry("dedupEntries", 1).containsEntry("acceptedMessages", 3L);
        }
    }

    @Test void optionalObservationsAreBoundedAndNeverReboundOnDuplicate() {
        var nanos = new AtomicLong();
        try (var lab = new MessageLab(0, new MessageSettings(1000, 10, 10, 1, 1), () -> 1000L, nanos::get, r -> true)) {
            lab.accept(MessageScenarioTest.send("first"), SENDER, "first-callback", MessageContentMode.LAB_JSON);
            var untracked = lab.accept(MessageScenarioTest.send("second"), SENDER, "second-callback", MessageContentMode.LAB_JSON);
            assertThat(untracked.get("callbackId")).isNull();
            lab.accept(MessageScenarioTest.send("third"), SENDER, null, MessageContentMode.TEXT);
            assertThat(lab.metrics()).containsEntry("messages", 2).containsEntry("evictedRecentRecords", 2L);
            var rows = (List<?>) lab.page(null, null, 0, 100).get("items");
            assertThat(((Map<?, ?>) rows.get(1)).containsKey("body")).isFalse();
            nanos.set(10_000_000); lab.tick();
            assertThat(lab.accept(MessageScenarioTest.send("second"), SENDER, "new-callback", MessageContentMode.LAB_JSON)).isEqualTo(untracked);
            assertThat(lab.metrics()).containsEntry("trackedMessages", 0).containsEntry("scheduled", 0).containsEntry("held", 0);
            assertThatThrownBy(() -> lab.accept(MessageScenarioTest.send("second", "different"), SENDER, null, MessageContentMode.TEXT))
                    .hasMessageContaining("conflict");
            lab.accept(MessageScenarioTest.send("fresh"), SENDER, "fresh-callback", MessageContentMode.TEXT);
            lab.act("g", "r", "fresh", "reply", Map.of("requestId", "r", "text", "reply"));
            lab.stop("g", "r");
            assertThat(lab.metrics()).containsEntry("messages", 0).containsEntry("replyIds", 0);
            assertThat(lab.acceptances(List.of("fresh")).get("missingIds")).isEqualTo(List.of());
        }
    }

    @Test void explicitHeldReleaseCannotOutliveItsWindowBehindTheCleanupBatch() {
        var nanos = new AtomicLong();
        try (var lab = new MessageLab(0, new MessageSettings(1000, 200, 10, 200, 1),
                () -> 1000L, nanos::get, r -> { throw new AssertionError("Expired callback escaped"); })) {
            lab.hold(true);
            for (int i = 0; i < 101; i++) lab.accept(MessageScenarioTest.send("m" + i), SENDER, "c" + i, MessageContentMode.LAB_JSON);
            nanos.set(10_000_000);
            assertThatThrownBy(() -> lab.release(List.of("receipt-101"))).hasMessageContaining("expired");
            assertThat(lab.metrics()).containsEntry("held", 0).containsEntry("trackedMessages", 0)
                    .containsEntry("expiredObservations", 101L).containsEntry("callbackOffered", 0L);
        }
    }

    @Test void summaryEvictionDoesNotDetachTrackedRecordsFromWorkerStop() {
        try (var lab = new MessageLab(0, new MessageSettings(1000, 10, 1000, 2, 1),
                () -> 1000L, () -> 0L, r -> true)) {
            lab.accept(MessageScenarioTest.send("first"), SENDER, "first", MessageContentMode.TEXT);
            lab.accept(MessageScenarioTest.send("second"), SENDER, "second", MessageContentMode.TEXT);
            assertThat(lab.metrics()).containsEntry("messages", 2).containsEntry("recentSentRecords", 1);
            assertThat((List<?>) lab.page(null, null, 0, 100).get("items")).hasSize(2);
            assertThat((List<?>) lab.page("g", "r", 0, 100).get("items")).hasSize(2);
            assertThat((List<?>) lab.page("g", "another", 0, 100).get("items")).isEmpty();
            lab.stop("g", "r");
            assertThat(lab.metrics()).containsEntry("messages", 0).containsEntry("trackedMessages", 0)
                    .containsEntry("recentSentRecords", 0).containsEntry("dedupEntries", 2);
        }
    }

    @Test void aTickCommitsAtMostOneHundredDueBusinessActionsAndCloseDropsPlans() {
        try (var lab = lab(1, () -> 1000L, r -> true)) {
            for (int i = 0; i < 101; i++) lab.accept(MessageScenarioTest.send("m" + i,
                    "{\"receipts_status\":[\"read\"],\"delayMs\":0}"), SENDER, "c" + i, MessageContentMode.LAB_JSON);
            lab.tick(); assertThat(lab.metrics()).containsEntry("scheduled", 1);
            lab.close(); lab.tick(); assertThat(lab.metrics()).containsEntry("scheduled", 0).containsEntry("callbackOffered", 201L);
            assertThatThrownBy(() -> lab.accept(MessageScenarioTest.send("closed"), SENDER, "closed", MessageContentMode.LAB_JSON)).hasMessageContaining("closed");
        }
    }
}
