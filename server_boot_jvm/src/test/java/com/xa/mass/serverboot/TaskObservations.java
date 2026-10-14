package com.xa.mass.serverboot;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Proof-local composition of three independent public observations, never an atomic snapshot. */
final class TaskObservations {
    private TaskObservations() { }

    static HttpRequest.Builder request(URI base, String path, TaskCaller caller) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("X-XA-Dev-User", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(caller.userId().getBytes(StandardCharsets.UTF_8)))
                .header("X-XA-Dev-Role", caller.role());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> detail(HttpClient http, URI base, TaskCaller caller, String project, String id) {
        try {
            var entry = read(http, request(base, "/api/v1/projects/" + project + "/tasks/" + id, caller).GET());
            var descriptor = (Map<String, Object>) entry.get("task");
            var counts = (Map<String, Object>) read(http, request(base, "/api/v1/projects/" + project + "/tasks:stats", caller)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(List.of(id))))).get(id);
            var tags = (Map<String, Object>) counts.get("countsByTag");
            var task = new LinkedHashMap<String, Object>((Map<String, Object>) descriptor.get("metadata"));
            for (String key : List.of("taskId", "name", "workerGroupId")) task.put(key, descriptor.get(key));
            task.put("state", entry.get("scoreBand"));
            task.put("createdAtMillis", entry.get("createdAtMillis"));
            task.put("managed", "PARK_WHEN_IDLE".equals(descriptor.get("idleDisposition")));
            task.put("failedCount", count(tags, 5, 5));
            if (project.equals("messages")) {
                task.put("sendTotal", counts.get("total"));
                task.put("sentCount", count(tags, 6, 9));
                task.put("deliveredCount", count(tags, 7, 9));
                task.put("readCount", count(tags, 8, 9));
                task.put("repliedCount", count(tags, 9, 9));
            } else {
                task.put("totalCount", counts.get("total"));
                task.put("activeCount", count(tags, 1, 1));
                task.put("succeededCount", count(tags, 6, 9));
            }
            var results = read(http, request(base, "/api/v1/" + project + "/tasks/" + id + "/results", caller).GET());
            assertThat(results).containsOnlyKeys("taskId", "results", "truncated").containsEntry("taskId", id);
            return Map.of("task", task, "results", results.get("results"), "resultsTruncated", results.get("truncated"));
        } catch (Exception failure) { throw new IllegalStateException("Task observations failed", failure); }
    }

    private static long count(Map<String, Object> tags, int first, int last) {
        long total = 0;
        for (int tag = first; tag <= last; tag++) total += ((Number) tags.get(Integer.toString(tag))).longValue();
        return total;
    }

    private static Map<String, Object> read(HttpClient http, HttpRequest.Builder request) throws Exception {
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Task observation HTTP status").isEqualTo(200);
        return Jsons.parseObject(response.body());
    }
}
