package com.xa.mass.server.task.directory;

import java.util.regex.Pattern;

/** Attribution encoding only. It has no account, role or authentication semantics. */
public final class TaskUserId {
    private static final Pattern FORMAT = Pattern.compile("[1-9a-z][0-9a-z]{3,8}");
    public static final long MAX_SCORE = (101_559_956_668_416L - 1) * 64;

    private TaskUserId() {}

    public static String normalize(String value) {
        if (value == null || !FORMAT.matcher(value.strip()).matches())
            throw new IllegalArgumentException("userId must be 4–9 lowercase Base36 characters without a leading zero");
        return value.strip();
    }

    public static long score(String value) { return Long.parseLong(normalize(value), 36) * 64; }

    static void validateScore(double score) {
        if (!Double.isFinite(score) || score < 0 || score > MAX_SCORE || score != Math.floor(score)
                || ((long) score & 63L) != 0)
            throw new IllegalStateException("Task directory score is invalid");
        if (score != 0) normalize(Long.toString((long) score / 64, 36));
    }
}
