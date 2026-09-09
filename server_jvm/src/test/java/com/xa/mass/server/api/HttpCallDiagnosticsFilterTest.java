package com.xa.mass.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpCallDiagnosticsFilterTest {
    @TempDir Path temporary;

    @Test void disabledDiagnosticsDoNotWrapOrRetainTheRequest() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/worker-delivery/endpoint-managers/private-id/direct-calls");
        var response = new MockHttpServletResponse();
        new HttpCallDiagnosticsFilter().doFilter(request, response,
                (supplied, target) -> assertThat(supplied).isSameAs(request));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "/api/v1/worker-delivery/endpoint-managers/private-id/direct-calls,DIRECT_CALL",
            "/api/v1/tasks/private-id/items:call,ITEMS_CALL"})
    void asyncTimeoutAndRepeatedCompletionProduceExactlyOneFinalObservation(String path, String operation) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        var async = new AtomicReference<MockAsyncContext>();
        Path recordingPath = temporary.resolve("async.jfr");
        try (var recording = new Recording()) {
            recording.enable("xa.mass.HttpInitial");
            recording.enable("xa.mass.HttpCompletion");
            recording.start();
            new HttpCallDiagnosticsFilter().doFilter(request, response, (supplied, target) -> {
                async.set((MockAsyncContext) ((HttpServletRequest) supplied).startAsync(supplied, target));
            });
            var event = new AsyncEvent(async.get());
            for (var listener : async.get().getListeners()) {
                listener.onTimeout(event);
                listener.onComplete(event);
                listener.onComplete(event);
            }
            recording.stop();
            recording.dump(recordingPath);
        }
        var events = RecordingFile.readAllEvents(recordingPath);
        assertThat(events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpInitial"))).hasSize(1);
        var completions = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpCompletion")).toList();
        assertThat(completions).hasSize(1);
        assertThat(completions.getFirst().getString("reason")).isEqualTo("timeout");
        assertThat(completions.getFirst().getString("operation")).isEqualTo(operation);
        assertThat(completions.getFirst().getEventType().getFields()).extracting(jdk.jfr.ValueDescriptor::getName)
                .doesNotContain("url", "workerId", "payload", "result", "requestId");
    }

    @Test void listenerFailureCannotFailAnAlreadyStartedAsyncRequest() {
        var context = mock(AsyncContext.class);
        var observation = new HttpCallDiagnosticsFilter.Observation("DIRECT_CALL", new MockHttpServletResponse());
        doThrow(new IllegalStateException("Already complete")).when(context).addListener(observation);
        assertThat(observation.listen(context)).isSameAs(context);
    }
}
