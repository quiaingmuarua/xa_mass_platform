package com.xa.mass.workerpack.tool.probe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.WorkerInvocation;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeWorkerPackTest {
    private static final Gson GSON = new Gson();

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
        JsonObject body = resultBody(result);
        assertEquals("SG", body.get("region").getAsString());
        assertEquals("VALID_E164", body.get("classification").getAsString());
        assertEquals("phone-fingerprint-a", body.get("traceLabel").getAsString());
    }

    @Test
    void phoneMetadataHandlerReturnsStructuredBusinessFailure() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.phoneMetadataHandler(), ProbeWorkerPack.PHONE_METADATA_EVENT,
                Map.of("phoneNumber", "123"));

        assertFalse(result.success());
        assertEquals("PHONE_METADATA_INVALID", result.resultCode());
        assertEquals(false, resultBody(result).get("valid").getAsBoolean());
    }

    @Test
    void urlDnsHandlerClassifiesReservedInvalidHostWithoutNetwork() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.urlDnsHandler(), ProbeWorkerPack.URL_DNS_EVENT,
                Map.of("url", "https://does-not-exist.public-probe.invalid/", "expectedOutcome", "DNS_NXDOMAIN"));

        assertFalse(result.success());
        assertEquals("DNS_NXDOMAIN", result.resultCode());
        assertEquals("does-not-exist.public-probe.invalid", resultBody(result).get("host").getAsString());
    }

    @Test
    void csvValidatorReportsColumnAndRowCounts() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.csvValidateHandler(), ProbeWorkerPack.CSV_VALIDATE_EVENT,
                Map.of("csv", "id,name\n1,Ada\n2,Lin", "requiredColumns", List.of("id", "name")));

        assertTrue(result.success());
        JsonObject body = resultBody(result);
        assertEquals("id", body.getAsJsonArray("columns").get(0).getAsString());
        assertEquals("name", body.getAsJsonArray("columns").get(1).getAsString());
        assertEquals(2, body.get("rowCount").getAsInt());
    }

    @Test
    void jsonSchemaValidatorReportsMissingRequiredFields() throws Exception {
        WorkerResult result = handle(ProbeWorkerPack.jsonSchemaHandler(), ProbeWorkerPack.JSON_SCHEMA_EVENT,
                Map.of("payload", Map.of("id", "1"), "requiredFields", List.of("id", "status")));

        assertFalse(result.success());
        assertEquals("SCHEMA_INVALID", result.resultCode());
        assertEquals("status", resultBody(result).getAsJsonArray("missingFields").get(0).getAsString());
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
        return handler.handle(WorkerInvocation.of(
                "corr-" + eventCode,
                eventCode,
                MassPayload.of(input),
                MassPayload.of(Map.of())));
    }

    private static JsonObject resultBody(WorkerResult result) {
        return GSON.fromJson(result.result(), JsonObject.class);
    }

    private static void assertBinding(WorkerGroupSpec spec, String eventCode, List<String> projectCodes) {
        WorkerEventBindingSpec binding = spec.eventBindings().stream()
                .filter(candidate -> eventCode.equals(candidate.eventCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(projectCodes, binding.projectCodes());
    }
}
