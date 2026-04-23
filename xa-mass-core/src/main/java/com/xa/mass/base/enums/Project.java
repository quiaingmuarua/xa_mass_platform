package com.xa.mass.base.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xa.mass.base.project.ProjectRegistry;

import java.util.Arrays;
import java.util.List;

/**
 * Built-in project seeds kept for compatibility.
 *
 * <p>Runtime project validation is handled by {@link ProjectRegistry}; this
 * enum is no longer the extensibility boundary.
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum Project {
    DEMO_APP("demoApp", "Demo App"),
    TEST_APP("testApp", "Test App"),
    CRAWLER_APP("crawlerApp", "Crawler"),
    RCS_APP("rcsApp", "GoogleRcs"),
    TELEGRAM_APP("telegramApp", "Telegram");

    private final String code;
    private final String name;

    Project(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * Resolve a built-in enum value by code.
     */
    public static Project fromCode(String code) {
        return Arrays.stream(values())
                .filter(project -> project.code.equals(code))
                .findFirst()
                .orElse(DEMO_APP);
    }

    public static Project requireCode(String code) {
        return Arrays.stream(values())
                .filter(project -> project.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported project code: " + code));
    }

    /**
     * Return all enabled runtime project codes.
     */
    public static List<String> getAllCodes() {
        return ProjectRegistry.listProjectCodes();
    }

    /**
     * Return all enabled runtime project names.
     */
    public static List<String> getAllNames() {
        return ProjectRegistry.listProjectNames();
    }

    /**
     * Check whether a runtime project code is enabled.
     */
    public static boolean isValidCode(String code) {
        return ProjectRegistry.isValidCode(code);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return code;
    }
}
