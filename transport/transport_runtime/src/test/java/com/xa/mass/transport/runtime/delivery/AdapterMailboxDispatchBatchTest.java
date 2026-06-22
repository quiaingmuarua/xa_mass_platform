package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdapterMailboxDispatchBatchTest {

    @Test
    void producerBatchCarriesMailboxKeyAndItemsOnly() {
        List<String> componentNames = Arrays.stream(AdapterMailboxDispatchBatch.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("adapterMailboxKey", "items"), componentNames);
    }

    @Test
    void carriesAdapterMailboxKeyDirectly() {
        DispatchMessage item = item();

        AdapterMailboxDispatchBatch batch = new AdapterMailboxDispatchBatch(
                "mailbox-a",
                List.of(item)
        );

        assertEquals("mailbox-a", batch.adapterMailboxKey());
    }

    @Test
    void rejectsEmptyOrNullItems() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> new AdapterMailboxDispatchBatch("mailbox-a", List.of())
        );
        assertEquals("items must not be empty", empty.getMessage());

        IllegalArgumentException nullItem = assertThrows(
                IllegalArgumentException.class,
                () -> new AdapterMailboxDispatchBatch("mailbox-a", Arrays.asList(item(), null))
        );
        assertEquals("items must not contain null", nullItem.getMessage());
    }

    private static DispatchMessage item() {
        return new DispatchMessage("delivery-1", "worker-1", "{}", "corr-1", 0L, 1L);
    }
}
