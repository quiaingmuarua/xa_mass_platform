package com.xa.mass.workerpack.tool.geo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoLookupToolTest {
    private static final Gson GSON = new Gson();

    @Test
    void lookupReturnsStableGeoProfile() {
        Map<String, Object> profile = GeoLookupTool.lookup("Shanghai");

        assertEquals("Shanghai", profile.get("city"));
        assertEquals("CN", profile.get("countryCode"));
        assertEquals("CNY", profile.get("currency"));
        assertEquals(GeoLookupTool.PROVIDER, profile.get("provider"));
        assertEquals(true, profile.get("simulated"));
    }

    @Test
    void workerPackGroupSpecBindsPublicToolEvent() {
        WorkerGroupSpec spec = GeoLookupWorkerPack.groupSpec(List.of("workerPackApp"));

        assertEquals(GeoLookupWorkerPack.WORKER_GROUP_ID, spec.groupId());
        assertEquals(1, spec.eventBindings().size());
        WorkerEventBindingSpec binding = spec.eventBindings().getFirst();
        assertEquals(GeoLookupTool.EVENT_CODE, binding.eventCode());
        assertEquals(List.of("workerPackApp"), binding.projectCodes());
        assertEquals(GeoLookupTool.PROVIDER, spec.defaultAttributes().get("provider"));
    }

    @Test
    void handlerConvertsDispatchPayloadIntoWorkerActionResult() throws Exception {
        WorkerActionResult result = GeoLookupWorkerPack.handler().handle(WorkerAction.of(
                "action-geo-1",
                "corr-geo-1",
                GeoLookupTool.EVENT_CODE,
                GSON.toJson(Map.of("query", "Singapore")),
                MassPayload.of(Map.of())));

        assertTrue(result.success());
        JsonObject body = resultBody(result);
        assertEquals("SG", body.get("countryCode").getAsString());
        assertEquals("worker-pack-geo", body.get("provider").getAsString());
    }

    @Test
    void handlerReturnsStructuredFailureForMissingQuery() throws Exception {
        WorkerActionResult result = GeoLookupWorkerPack.handler().handle(WorkerAction.of(
                "action-geo-2",
                "corr-geo-2",
                GeoLookupTool.EVENT_CODE,
                GSON.toJson(Map.of()),
                MassPayload.of(Map.of())));

        assertFalse(result.success());
        assertEquals("INVALID_GEO_QUERY", result.code());
    }

    @Test
    void handlerClassifiesProviderFailureAsBusinessResult() throws Exception {
        GeoLookupProvider provider = new GeoLookupProvider() {
            @Override
            public String providerId() {
                return "geo-provider-test";
            }

            @Override
            public GeoLookupResult lookup(GeoLookupRequest request) {
                throw new GeoLookupProviderException("GEO_PROVIDER_TIMEOUT", "geo provider timed out");
            }
        };

        WorkerActionResult result = GeoLookupWorkerPack.handler(provider).handle(WorkerAction.of(
                "action-geo-3",
                "corr-geo-3",
                GeoLookupTool.EVENT_CODE,
                GSON.toJson(Map.of("query", "Singapore")),
                MassPayload.of(Map.of())));

        assertFalse(result.success());
        assertEquals("GEO_PROVIDER_TIMEOUT", result.code());
        JsonObject body = resultBody(result);
        assertEquals("Singapore", body.get("query").getAsString());
        assertEquals("geo-provider-test", body.get("provider").getAsString());
    }

    private static JsonObject resultBody(WorkerActionResult result) {
        return GSON.fromJson(result.body(), JsonObject.class);
    }
}
