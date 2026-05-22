package com.xa.mass.testing.workerfault;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

public final class WorkerFaultScenarioCli {

    private WorkerFaultScenarioCli() {
    }

    public static void main(String[] args) throws Exception {
        WorkerFaultScenarioIndex.Scenario scenario = resolveScenario(args);
        System.setProperty(
                "mass.sdk.chaos.forceExit",
                System.getProperty("mass.sdk.chaos.forceExit", "false")
        );
        invokeMain(scenario.runnerFamily().mainClassName(), tailArgs(args));
    }

    static WorkerFaultScenarioIndex.Scenario resolveScenario(String[] args) {
        String scenarioId = scenarioIdFromArgs(args);
        return WorkerFaultScenarioIndex.scenarioForId(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("unknown worker fault scenarioId: " + scenarioId));
    }

    private static String scenarioIdFromArgs(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("worker fault scenarioId is required as the first argument");
        }
        String first = args[0].trim();
        if (first.startsWith("--scenario-id=")) {
            return first.substring("--scenario-id=".length()).trim();
        }
        if (first.startsWith("--scenario=")) {
            return first.substring("--scenario=".length()).trim();
        }
        if (first.startsWith("--")) {
            throw new IllegalArgumentException("worker fault scenarioId is required before option " + first);
        }
        return first.toLowerCase(Locale.ROOT);
    }

    private static String[] tailArgs(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static void invokeMain(String mainClassName, String[] args) throws Exception {
        Class<?> runnerClass = Class.forName(mainClassName);
        Method main = runnerClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
