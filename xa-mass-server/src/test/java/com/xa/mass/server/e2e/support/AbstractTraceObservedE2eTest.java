package com.xa.mass.server.e2e.support;

import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceOperatorService;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public abstract class AbstractTraceObservedE2eTest extends AbstractSampleE2eTest {

    protected static Path traceOutputDir(String scenarioDirName) {
        return Path.of("target", scenarioDirName, UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
    }

    protected static void registerTraceOutputDir(DynamicPropertyRegistry registry, Path traceOutputDir) {
        registry.add("mass.trace.sink.output-dir", () -> traceOutputDir.toString());
    }

    protected TraceAnalyzeResponse awaitTraceScenarioOk(Path traceOutputDir,
                                                        String scenarioId,
                                                        String target)
            throws InterruptedException {
        TraceAnalyzeResponse latestResponse = null;
        Exception latestException = null;
        TraceOperatorService traceOperator = new TraceOperatorService();
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                latestResponse = traceOperator.analyze(new TraceAnalyzeRequest(
                        traceOutputDir.toString(),
                        scenarioId,
                        target
                ));
                if (latestResponse.ok()) {
                    return latestResponse;
                }
            } catch (Exception e) {
                latestException = e;
            }
            TimeUnit.MILLISECONDS.sleep(200L);
        }
        if (latestException != null) {
            throw new AssertionError("trace scenario analysis failed before canonical JSONL became readable",
                    latestException);
        }
        throw new AssertionError("trace scenario analysis did not pass. Last response=" + latestResponse);
    }
}
