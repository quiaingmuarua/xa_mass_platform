package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.AdapterResultIngressEntries;
import com.xa.mass.transport.runtime.AdapterResultIngressSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Runtime-owned default processor for adapter inbound result frames.
 */
public final class AdapterInboundResultProcessor<T> {
    private static final Logger logger = LoggerFactory.getLogger(AdapterInboundResultProcessor.class);

    private final AdapterResultFrameReader<T> resultFrameReader;
    private final AdapterResultIngressSink resultIngressSink;
    private final AdapterResultDiagnosticsProvider<T> diagnosticsProvider;

    private AdapterInboundResultProcessor(AdapterResultFrameReader<T> resultFrameReader,
                                          AdapterResultIngressSink resultIngressSink,
                                          AdapterResultDiagnosticsProvider<T> diagnosticsProvider) {
        this.resultFrameReader = Objects.requireNonNull(resultFrameReader, "resultFrameReader");
        this.resultIngressSink = resultIngressSink;
        this.diagnosticsProvider = Objects.requireNonNull(diagnosticsProvider, "diagnosticsProvider");
    }

    public static <T> AdapterInboundResultProcessor<T> with(
            AdapterResultFrameReader<T> resultFrameReader,
            AdapterResultIngressSink resultIngressSink,
            AdapterResultDiagnosticsProvider<T> diagnosticsProvider) {
        return new AdapterInboundResultProcessor<>(resultFrameReader, resultIngressSink, diagnosticsProvider);
    }

    public AdapterResultProcessOutcome processResult(T resultFrame) {
        if (resultIngressSink == null) {
            logger.warn("Adapter result ignored because result ingress sink is unavailable");
            return AdapterResultProcessOutcome.REJECTED;
        }
        try {
            AdapterResultFrame result = resultFrameReader.read(resultFrame);
            Map<String, String> diagnostics = diagnosticsProvider.diagnostics(resultFrame, result);
            ResultIngressEntry entry = AdapterResultIngressEntries.from(
                    result.correlationRef(),
                    result.payload(),
                    diagnostics
            );
            boolean accepted = resultIngressSink.ingest(entry);
            if (!accepted) {
                logger.error("Adapter result ingress sink rejected inbound result: correlationRef={}",
                        result.correlationRef());
                return AdapterResultProcessOutcome.FAILED;
            }
            return AdapterResultProcessOutcome.INGESTED;
        } catch (IllegalArgumentException ex) {
            logger.warn("Adapter result rejected: {}", ex.getMessage());
            return AdapterResultProcessOutcome.REJECTED;
        } catch (RuntimeException ex) {
            logger.error("Adapter result processing failed", ex);
            return AdapterResultProcessOutcome.FAILED;
        }
    }
}
