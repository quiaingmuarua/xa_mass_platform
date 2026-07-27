package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import java.util.List;

public interface SeedResultRuntime {

    int appendSeedResults(List<SeedResult> results);

    List<SeedResult> consumeSeedResults(
            SeedResultOutcomeClass outcomeClass,
            int limit
    );
}
