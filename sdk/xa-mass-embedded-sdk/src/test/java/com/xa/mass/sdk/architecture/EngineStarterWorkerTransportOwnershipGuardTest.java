package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineStarterWorkerTransportOwnershipGuardTest {

    private static final Pattern TRANSPORT_OWNER_IMPORT = Pattern.compile(
            "\\bimport\\s+com\\.xa\\.mass\\.transport\\.");

    private static final Pattern TRANSPORT_OWNER_TOKEN = Pattern.compile(String.join("|", List.of(
            "\\bEmbeddedTransportAssembly\\b",
            "\\bAssignedDeliverySink\\b",
            "\\bCurrentSessionDisconnect",
            "\\bEndpointLease",
            "\\bDeliveryPullChannel\\b",
            "\\bPollingPendingDelivery"
    )));

    @Test
    void engineStarterDoesNotAbsorbTransportAssemblyOrAdapterLifecycle() {
        List<String> violations = new ArrayList<>();
        for (Path sourcePath : EngineCallerSurfaceGuardSupport.javaSourceFiles(
                "xa-mass-engine-starter/src/main/java")) {
            String source = EngineCallerSurfaceGuardSupport.read(sourcePath);
            if (TRANSPORT_OWNER_IMPORT.matcher(source).find() || TRANSPORT_OWNER_TOKEN.matcher(source).find()) {
                violations.add(sourcePath.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "engine-starter may accept transport/worker ports, but must not own transport assembly, "
                        + "adapter lifecycle, session, endpoint lease, or polling delivery semantics:\n"
                        + String.join("\n", violations));
    }

    @Test
    void embeddedApplicationRemainsTheTransportAssemblyHost() {
        String massApplication = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");

        assertTrue(massApplication.contains("EmbeddedTransportAssembly"),
                "MassApplication remains the embedded transport assembly host for this ECSP slice");
    }
}
