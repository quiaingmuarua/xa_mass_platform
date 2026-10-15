package com.xa.mass.kernel.redis;

/** Fixed keys shared only by the Kernel's atomic Task retention fence. */
public final class TaskRedisKeys {
    private TaskRedisKeys() { }
    public static String descriptor(RedisKeyspace space, String id) { return space.base() + ":task:" + id + ":descriptor"; }
    public static String items(RedisKeyspace space, String id) { return space.base() + ":task:" + id + ":items"; }
    public static String results(RedisKeyspace space, String id) { return space.base() + ":task:" + id + ":results"; }
    public static String itemScores(RedisKeyspace space, String id) { return space.base() + ":task:" + id + ":item_score"; }
    public static String scores(RedisKeyspace space) { return space.base() + ":task:score"; }
}
