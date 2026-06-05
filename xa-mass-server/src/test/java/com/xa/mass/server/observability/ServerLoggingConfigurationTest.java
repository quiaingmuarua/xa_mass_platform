package com.xa.mass.server.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.xa.mass.api.observability.ServerApiFailureLogger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLoggingConfigurationTest {

    @Test
    void runtimeLogbackContextRoutesServerApiFailuresToDedicatedAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(ServerApiFailureLogger.LOGGER_NAME);
        Appender<?> appender = logger.getAppender("SERVER_API_FAILURE_FILE");

        assertTrue(!logger.isAdditive(), "SERVER_API_FAILURE logger must not write through the root appenders");
        assertTrue(logger.getLevel() == null || Level.INFO.equals(logger.getLevel()),
                "SERVER_API_FAILURE logger must be INFO or inherit INFO");
        assertNotNull(appender, "SERVER_API_FAILURE logger must have SERVER_API_FAILURE_FILE appender");
        assertRollingAppender(appender, "xa-mass-server-api-failure");
    }

    @Test
    void runtimeLogbackContextHasBoundedNormalAndErrorFiles() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        assertRollingAppender(root.getAppender("FILE"), "xa-mass-platform");
        assertRollingAppender(root.getAppender("ERROR_FILE"), "xa-mass-platform-error");
    }

    @Test
    void activeLogbackConfigUsesCurrentRollingPolicyAndTotalSizeCap() throws IOException {
        String activeConfig = Files.readString(Path.of("src/main/resources/logback.xml"), StandardCharsets.UTF_8);

        assertTrue(!activeConfig.contains("SizeAndTimeBasedFNATP"),
                "active logback.xml must not use deprecated SizeAndTimeBasedFNATP");
        assertTrue(activeConfig.contains("SizeAndTimeBasedRollingPolicy"),
                "active logback.xml must use SizeAndTimeBasedRollingPolicy");
        assertTrue(activeConfig.contains("<totalSizeCap>"),
                "all rolling file appenders must define totalSizeCap");
        assertTrue(activeConfig.contains("SERVER_API_FAILURE_FILE")
                        && activeConfig.contains("xa-mass-server-api-failure.log"),
                "active logback.xml must define the server API failure file lane");
    }

    private void assertRollingAppender(Appender<?> appender, String expectedFileNamePart) {
        assertNotNull(appender, "missing appender containing " + expectedFileNamePart);
        RollingFileAppender<?> rolling = assertInstanceOf(RollingFileAppender.class, appender);
        assertTrue(rolling.getFile().contains(expectedFileNamePart),
                "appender file must contain " + expectedFileNamePart + ": " + rolling.getFile());
        assertInstanceOf(SizeAndTimeBasedRollingPolicy.class, rolling.getRollingPolicy(),
                "appender must use SizeAndTimeBasedRollingPolicy");
    }
}
