package com.xa.mass.engine.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xa.mass.engine.TraceEventLogger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TraceEventLogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    public TraceEventLogCapture() {
        this.logger = (Logger) LoggerFactory.getLogger(TraceEventLogger.class);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    public List<ILoggingEvent> events(String eventName) {
        List<ILoggingEvent> snapshot = List.copyOf(appender.list);
        return snapshot.stream()
                .filter(event -> eventName.equals(event.getMDCPropertyMap().get("event")))
                .collect(Collectors.toList());
    }

    public void assertHasEvent(String eventName, Predicate<Map<String, String>> predicate) {
        assertTrue(events(eventName).stream()
                        .map(ILoggingEvent::getMDCPropertyMap)
                        .anyMatch(predicate),
                "Expected trace event " + eventName + " with matching MDC fields");
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
