package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Objects;

/** Command plus its Worker-address-bearing remote entry key. */
record DeliveryCommandItem(
        String entryKey,
        DeliveryCommand command
) {
    DeliveryCommandItem {
        if (entryKey == null || entryKey.isBlank()) {
            throw new IllegalArgumentException("entryKey must be non-blank");
        }
        Objects.requireNonNull(command, "command");
    }
}
