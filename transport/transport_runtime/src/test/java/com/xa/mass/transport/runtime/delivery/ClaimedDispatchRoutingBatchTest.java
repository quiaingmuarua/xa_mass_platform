package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.routing.RoutingTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimedDispatchRoutingBatchTest {

    @Test
    void claimedBatchAddsReferencesOnlyAfterMaterialization() {
        List<String> componentNames = Arrays.stream(ClaimedDispatchRoutingBatch.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("batch", "references"), componentNames);

        DispatchRoutingBatch batch = batch();
        DispatchHandoffReference reference = new DispatchHandoffReference("mailbox-a", "delivery-1");
        ClaimedDispatchRoutingBatch claimed = new ClaimedDispatchRoutingBatch(batch, List.of(reference));

        assertEquals("mailbox-a", claimed.adapterMailboxKey());
        assertEquals(batch.items(), claimed.items());
        assertEquals(List.of(reference), claimed.references());
    }

    @Test
    void rejectsNullReferences() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ClaimedDispatchRoutingBatch(batch(), Arrays.asList(
                        new DispatchHandoffReference("mailbox-a", "delivery-1"),
                        null
                ))
        );

        assertEquals("references must not contain null", error.getMessage());
    }

    private static DispatchRoutingBatch batch() {
        return new DispatchRoutingBatch(
                RoutingTarget.adapterMailbox("mailbox-a"),
                List.of(new DispatchRoutingItem("delivery-1", "worker-1", "{}", "corr-1", 0L, 1L))
        );
    }
}
