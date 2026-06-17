package com.xa.mass.workerpack.tool.probe;

import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.handler.WorkerInvocation;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeWorkerPackTest {
    @Test
    void phoneMetadataHandlerReturnsDeterministicMetadata() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.phoneMetadataHandler(), ProbeWorkerPack.PHONE_METADATA_EVENT,
                Map.of(
                        "phoneNumber", "+6591234567",
                        "defaultRegion", "SG",
                        "expectedOutcome", "VALID_E164",
                        "traceLabel", "phone-fingerprint-a"
                ));

        assertTrue(result.success());
        assertEquals("SG", result.output().get("region"));
        assertEquals("VALID_E164", result.output().get("classification"));
        assertEquals("phone-fingerprint-a", result.output().get("traceLabel"));
    }

    @Test
    void phoneMetadataHandlerReturnsStructuredBusinessFailure() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.phoneMetadataHandler(), ProbeWorkerPack.PHONE_METADATA_EVENT,
                Map.of("phoneNumber", "123"));

        assertFalse(result.success());
        assertEquals("PHONE_METADATA_INVALID", result.errorCode());
        assertEquals(false, result.output().get("valid"));
    }

    @Test
    void urlDnsHandlerClassifiesReservedInvalidHostWithoutNetwork() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.urlDnsHandler(), ProbeWorkerPack.URL_DNS_EVENT,
                Map.of("url", "https://does-not-exist.public-probe.invalid/", "expectedOutcome", "DNS_NXDOMAIN"));

        assertFalse(result.success());
        assertEquals("DNS_NXDOMAIN", result.errorCode());
        assertEquals("does-not-exist.public-probe.invalid", result.output().get("host"));
    }

    @Test
    void csvValidatorReportsColumnAndRowCounts() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.csvValidateHandler(), ProbeWorkerPack.CSV_VALIDATE_EVENT,
                Map.of("csv", "id,name\n1,Ada\n2,Lin", "requiredColumns", List.of("id", "name")));

        assertTrue(result.success());
        assertEquals(List.of("id", "name"), result.output().get("columns"));
        assertEquals(2, result.output().get("rowCount"));
    }

    @Test
    void jsonSchemaValidatorReportsMissingRequiredFields() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.jsonSchemaHandler(), ProbeWorkerPack.JSON_SCHEMA_EVENT,
                Map.of("payload", Map.of("id", "1"), "requiredFields", List.of("id", "status")));

        assertFalse(result.success());
        assertEquals("SCHEMA_INVALID", result.errorCode());
        assertEquals(List.of("status"), result.output().get("missingFields"));
    }

    @Test
    void workerGroupSpecsBindScenarioEventNames() {
        WorkerGroupSpec phone = ProbeWorkerPack.phoneDeviceGroupSpec(List.of("deviceProbe"));
        WorkerGroupSpec url = ProbeWorkerPack.urlDnsGroupSpec(List.of("publicProbe"));
        WorkerGroupSpec dataQuality = ProbeWorkerPack.dataQualityGroupSpec(List.of("dataQualityProbe"));

        assertBinding(phone, ProbeWorkerPack.PHONE_METADATA_EVENT, List.of("deviceProbe"));
        assertBinding(url, ProbeWorkerPack.URL_DNS_EVENT, List.of("publicProbe"));
        assertBinding(dataQuality, ProbeWorkerPack.CSV_VALIDATE_EVENT, List.of("dataQualityProbe"));
        assertBinding(dataQuality, ProbeWorkerPack.JSON_SCHEMA_EVENT, List.of("dataQualityProbe"));
    }

    private static WorkerResult handle(WorkerEventHandler handler, String eventCode, Map<String, Object> input)
            throws Exception {
        return handler.handle(new WorkerInvocation(
                eventCode,
                MassPayload.of(input),
                MassPayload.of(Map.of())));
    }

    private static void assertBinding(WorkerGroupSpec spec, String eventCode, List<String> projectCodes) {
        WorkerEventBindingSpec binding = spec.eventBindings().stream()
                .filter(candidate -> eventCode.equals(candidate.eventCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(projectCodes, binding.projectCodes());
    }
}
