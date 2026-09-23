package com.xa.mass.workersimulator.messaging;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MessageLabTest {
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
        try (var lab = new MessageLab(42, now::get, receipt -> { receipts.add(receipt); return false; })) {
            var request = MessageScenarioTest.send("m", "{\"receipts_status\":[\"read\",\"replied\"],\"delayMs\":1000,\"text\":\"r\"}");
            var first = lab.accept(request, SENDER, "callback");
            assertThat(receipts).hasSize(1); assertThat(receipts.getFirst().snapshot()).containsEntry("status", "DELIVERED");
            assertThat(lab.accept(request, SENDER, "other")).isEqualTo(first);
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
        try (var lab = new MessageLab(1, () -> 1000L, r -> { receipts.add(r); return true; })) {
            lab.accept(MessageScenarioTest.send("m", "{\"receipts_status\":[\"read\"],\"probability\":1,\"delayMs\":0}"), SENDER, "c");
            lab.tick(); assertThat(receipts).hasSize(1); assertThat(lab.metrics()).containsEntry("scheduled", 0);
        }
    }

    @Test void fullHoldCapacityRejectsBeforeFactCommitAndEndsOnlyTheAffectedAutomaticPlan() {
        var now = new AtomicLong(1000);
        try (var lab = new MessageLab(1, now::get, r -> { throw new AssertionError("Held callback escaped"); })) {
            lab.hold(true);
            lab.accept(MessageScenarioTest.send("m", "{\"receipts_status\":[\"replied\"],\"delayMs\":1000,\"text\":\"auto\"}"), SENDER, "c");
            for (int i = 1; i < MessageScenario.MAX_HELD; i++) lab.act("g", "r", "m", "reply", Map.of("requestId", "r" + i, "text", "reply"));
            var previous = lab.page(null, null, 0, 1);
            assertThatThrownBy(() -> lab.act("g", "r", "m", "reply", Map.of("requestId", "overflow", "text", "no")))
                    .hasMessageContaining("capacity exhausted");
            assertThat(lab.page(null, null, 0, 1)).isEqualTo(previous);
            assertThatThrownBy(() -> lab.accept(MessageScenarioTest.send("other"), SENDER, "c2")).hasMessageContaining("capacity exhausted");
            assertThat(lab.metrics()).containsEntry("messages", 1);
            now.set(2000); lab.tick();
            var row = (Map<?,?>)((List<?>)lab.page(null, null, 0, 1).get("items")).getFirst();
            assertThat(row.get("replyRequestId")).isEqualTo("r9999");
            assertThat(((Map<?,?>)row.get("plan")).get("failure")).isEqualTo("business_action_rejected");
            assertThat(lab.metrics()).containsEntry("scheduled", 0);
        }
    }

    @Test void aTickCommitsAtMostOneHundredDueBusinessActionsAndCloseDropsPlans() {
        try (var lab = new MessageLab(1, () -> 1000L, r -> true)) {
            for (int i = 0; i < 101; i++) lab.accept(MessageScenarioTest.send("m" + i,
                    "{\"receipts_status\":[\"read\"],\"delayMs\":0}"), SENDER, "c" + i);
            lab.tick(); assertThat(lab.metrics()).containsEntry("scheduled", 1);
            lab.close(); lab.tick(); assertThat(lab.metrics()).containsEntry("scheduled", 0).containsEntry("callbackOffered", 201L);
            assertThatThrownBy(() -> lab.accept(MessageScenarioTest.send("closed"), SENDER, "closed")).hasMessageContaining("closed");
        }
    }
}
