package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One proof-owned Redis scope with exact, non-destructive cleanup. */
public final class RedisTestScope {

    private static final Pattern LANE_PATTERN = Pattern.compile(
            "[a-z0-9_]+"
    );
    private static final System.Logger LOGGER = System.getLogger(
            RedisTestScope.class.getName()
    );

    private final String scope;
    private final String runToken;
    private final RedisKeyspace keyspace;

    private RedisTestScope(String scope, String runToken) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.runToken = Objects.requireNonNull(runToken, "runToken");
        this.keyspace = new RedisKeyspace(scope);
        if (!scope.startsWith("test_")
                || !scope.endsWith("_" + runToken)) {
            throw new IllegalArgumentException(
                    "Redis test cleanup requires its exact test scope"
            );
        }
    }

    public static RedisTestScope create(String lane) {
        if (lane == null || !LANE_PATTERN.matcher(lane).matches()) {
            throw new IllegalArgumentException(
                    "Redis test lane must contain lowercase words"
            );
        }
        String runToken = Instant.now().getEpochSecond()
                + "_"
                + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8);
        return new RedisTestScope(
                "test_" + lane + "_" + runToken,
                runToken
        );
    }

    public String scope() {
        return scope;
    }

    public RedisKeyspace keyspace() {
        return keyspace;
    }

    public String pattern() {
        return keyspace.base() + ":*";
    }

    public long cleanup(RedisCommands<String, String> commands) {
        Objects.requireNonNull(commands, "commands");
        List<String> keys = new ArrayList<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            KeyScanCursor<String> page = commands.scan(
                    cursor,
                    ScanArgs.Builder.matches(pattern()).limit(100)
            );
            keys.addAll(page.getKeys());
            cursor = page;
        } while (!cursor.isFinished());

        long removed = 0;
        for (int offset = 0; offset < keys.size(); offset += 100) {
            List<String> batch = keys.subList(
                    offset,
                    Math.min(offset + 100, keys.size())
            );
            removed += commands.unlink(batch.toArray(String[]::new));
        }
        LOGGER.log(
                System.Logger.Level.INFO,
                "cleaned Redis test scope scope={0} observedKeys={1} "
                        + "removedKeys={2}",
                scope,
                keys.size(),
                removed
        );
        return removed;
    }
}
