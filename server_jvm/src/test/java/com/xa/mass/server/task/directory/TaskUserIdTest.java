package com.xa.mass.server.task.directory;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskUserIdTest {
    @Test void boundedBase36AndReservedBitsAreExactlyRepresentable() {
        for (String id : new String[]{"1000", "1001", "zzzz", "zzzzzzzzz"}) {
            long score = TaskUserId.score(id);
            assertThat((long) (double) score).isEqualTo(score);
            assertThat(score & 63).isZero();
            assertThat(Long.toString(score / 64, 36)).isEqualTo(id);
            TaskUserId.validateScore(score);
        }
        assertThat(TaskUserId.score("zzzzzzzzz") + 63).isEqualTo(6_499_837_226_778_623L).isLessThan(9_007_199_254_740_991L);
        assertThat(TaskUserId.normalize("  1001  ")).isEqualTo("1001");
        for (String invalid : new String[]{"", "abc", "0001", "ABC1", "abc-def", "zzzzzzzzzz", "用户一"})
            assertThatThrownBy(() -> TaskUserId.normalize(invalid)).isInstanceOf(IllegalArgumentException.class);
        for (double invalid : new double[]{-1, 1.5, 64, TaskUserId.score("1001") + 1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThatThrownBy(() -> TaskUserId.validateScore(invalid)).isInstanceOf(RuntimeException.class);
    }

    @Test void metadataHasOneCanonicalOwnerAndTimeAndRejectsCallerReservedFields() {
        var input = TaskAttribution.input(Map.of("scenario", "custom"));
        assertThatThrownBy(() -> TaskAttribution.input(Map.of("userId", "1001"))).isInstanceOf(IllegalArgumentException.class);
        var attribution = new TaskAttribution("p", 123, "1001");
        assertThat(TaskAttribution.read(attribution.stamp(input))).isEqualTo(attribution);
        assertThat(TaskAttribution.read(Map.of("other", "opaque"))).isNull();
        assertThatThrownBy(() -> TaskAttribution.input(Map.of("server.future", "value"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskAttribution.read(Map.of(TaskAttribution.PROJECT, "p"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskAttribution.read(Map.of(TaskAttribution.PROJECT, "p", TaskAttribution.CREATED, "01"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TaskAttribution("p", 1, null).score()).isZero();
    }
}
