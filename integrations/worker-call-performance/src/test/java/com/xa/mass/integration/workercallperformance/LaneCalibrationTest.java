package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaneCalibrationTest {

    @Test
    void fixedWorkloadCountsDigestsForTheRequestedTime() throws Exception {
        long operations = LaneCalibration.digestFor(50);
        assertThat(operations).isPositive().isEqualTo(operations / 256 * 256);
    }

    @Test
    void calibrationPhaseWritesSingleAndParallelHostSpeed(@TempDir Path folder) throws Exception {
        LaneCalibration.run(Map.of("--output", folder.toString()));
        var result = Jsons.parseObject(Files.readString(folder.resolve("calibrate.json")));
        assertThat(result).containsEntry("status", "passed").containsEntry("workload", "md5-64-bytes");
        assertThat(((Number) result.get("cpuSingleOpsPerSecond")).doubleValue()).isPositive();
        assertThat(((Number) result.get("cpuParallelOpsPerSecond")).doubleValue()).isPositive();
    }
}
