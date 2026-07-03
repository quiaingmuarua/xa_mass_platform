package com.xa.mass.runtime.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisTaskWorkKeyspaceTest {

    @Test
    void buildsStableDefaultKeys() {
        RedisTaskWorkKeyspace keyspace = new RedisTaskWorkKeyspace();

        assertEquals("xa:mass:runtime:v1:ready:tasks", keyspace.readyTasksZset());
        assertEquals("xa:mass:runtime:v1:delayed:work", keyspace.delayedWorkZset());
        assertEquals("xa:mass:runtime:v1:lease:expiry", keyspace.leaseExpiryZset());
        assertEquals("xa:mass:runtime:v1:stats", keyspace.runtimeStatsHash());
        assertEquals("xa:mass:runtime:v1:tasks", keyspace.taskRegistrySet());
        assertEquals("xa:mass:runtime:v1:task:task-1:ready", keyspace.taskReadyQueue("task-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:delayed", keyspace.taskDelayedZset("task-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:work:msg-1", keyspace.taskWorkHash("task-1", "msg-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:lease:msg-1", keyspace.taskLeaseHash("task-1", "msg-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:active", keyspace.taskActiveSet("task-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:members", keyspace.taskMembersSet("task-1"));
        assertEquals("xa:mass:runtime:v1:task:task-1:stats", keyspace.taskStatsHash("task-1"));
        assertEquals("xa:mass:runtime:v1:worker:worker-1:active", keyspace.workerActiveSet("worker-1"));
    }

    @Test
    void normalizesCustomNamespace() {
        RedisTaskWorkKeyspace keyspace = new RedisTaskWorkKeyspace("platform:test:");

        assertEquals("platform:test", keyspace.namespace());
        assertEquals("platform:test:ready:tasks", keyspace.readyTasksZset());
    }

    @Test
    void workMemberRoundTripsArbitraryIdentifiers() {
        RedisTaskWorkKeyspace keyspace = new RedisTaskWorkKeyspace();

        String member = keyspace.workMember("task:with:colon", "msg/with/slash");
        RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);

        assertEquals("task:with:colon", ref.taskId());
        assertEquals("msg/with/slash", ref.messageId());
    }

    @Test
    void rejectsMalformedWorkMember() {
        RedisTaskWorkKeyspace keyspace = new RedisTaskWorkKeyspace();

        assertThrows(IllegalArgumentException.class, () -> keyspace.parseWorkMember("bad-member"));
        assertThrows(IllegalArgumentException.class, () -> keyspace.parseWorkMember("dGFzaw:not-base64!"));
    }
}
