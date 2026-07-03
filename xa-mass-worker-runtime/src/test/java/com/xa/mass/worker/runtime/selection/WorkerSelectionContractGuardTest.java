package com.xa.mass.worker.runtime.selection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSelectionContractGuardTest {

    @Test
    void selectedWorkerHandlePublicSurfaceStaysMinimal() {
        Set<String> publicMethodNames = java.util.Arrays.stream(SelectedWorkerHandle.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "of",
                "workerId",
                "workerGroupId",
                "selectionToken",
                "exclusiveWorkerLock",
                "toClaimTarget"
        ), publicMethodNames);
    }

    @Test
    void claimAuthorizationStaysHiddenBehindSelectedHandle() {
        assertFalse(Modifier.isPublic(SelectedWorkerClaimAuthorization.class.getModifiers()));
        assertFalse(Modifier.isProtected(SelectedWorkerClaimAuthorization.class.getModifiers()));
    }

    @Test
    void selectionPackageDoesNotDependOnEngineOrExposeWorkerFacts() throws Exception {
        Path selectionRoot = Path.of("src/main/java/com/xa/mass/worker/runtime/selection");
        String source = Files.walk(selectionRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(WorkerSelectionContractGuardTest::readString)
                .collect(Collectors.joining("\n"));

        assertFalse(source.contains("com.xa.mass.engine"), "selection package must not depend on engine");
        for (String forbiddenPublicGetter : Set.of(
                "public Map<String, String> attributes",
                "public WorkerLoadSnapshot",
                "public WorkerReachabilityState",
                "public boolean dispatchEnabled",
                "public Set<String> supportedEventCodes",
                "public List<String> supportedEventCodes",
                "public List<String> supportedProjects"
        )) {
            assertFalse(source.contains(forbiddenPublicGetter),
                    "selection package exposes worker fact getter: " + forbiddenPublicGetter);
        }
        assertTrue(source.contains("final class SelectedWorkerClaimAuthorization"),
                "claim authorization should remain package-private");
    }

    @Test
    void selectionOwnerDoesNotGateOnReachabilityProjectionDirectly() throws Exception {
        String source = readString(Path.of("src/main/java/com/xa/mass/worker/runtime/selection/WorkerSelectionOwner.java"));

        for (String forbidden : Set.of(
                "getWorkerReachability(",
                "WorkerReachabilityState.ONLINE",
                "worker transport unreachable"
        )) {
            assertFalse(source.contains(forbidden),
                    "selection must consume worker-runtime dispatch eligibility, not direct reachability projection: "
                            + forbidden);
        }
    }

    @Test
    void selectionSchedulingViewDoesNotExposeReachabilityProjection() {
        String source = readString(Path.of("src/main/java/com/xa/mass/worker/runtime/evidence/WorkerSchedulingViewRuntime.java"));

        assertFalse(source.contains("getWorkerReachability("),
                "selection-facing scheduling view must not expose reachability as a candidate gate");
        assertFalse(source.contains("WorkerReachabilityState"),
                "reachability belongs to a separate diagnostic/evidence view, not the selection scheduling view");
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("failed to read " + path, e);
        }
    }
}
