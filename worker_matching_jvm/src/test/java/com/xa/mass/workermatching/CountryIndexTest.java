package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CountryIndexTest {
    @Test void exhaustiveBase26AndExactScoreCoordinates() {
        var codes = new HashSet<Integer>();
        for (char first = 'A'; first <= 'Z'; first++) {
            for (char second = 'A'; second <= 'Z'; second++) {
                String country = "" + first + second;
                int code = CountryIndex.code(country);
                assertTrue(codes.add(code));
                assertEquals(country, "" + (char) ('A' + code / 26) + (char) ('A' + code % 26));
                for (long low : new long[]{0, 1, 1000000000123L, CountryIndex.TIME_SCALE - 1}) {
                    long score = CountryIndex.score(country, CountryIndex.EPOCH_MILLIS + low);
                    assertEquals(score, (long) (double) score);
                    assertEquals(low, score % CountryIndex.TIME_SCALE);
                    assertEquals(code, score / CountryIndex.TIME_SCALE);
                }
            }
        }
        assertEquals(676, codes.size());
        assertEquals(65, CountryIndex.code("CN"));
        assertEquals(157, CountryIndex.code("GB"));
        assertEquals(538, CountryIndex.code("US"));
        assertEquals(675, CountryIndex.code("ZZ"));
    }

    @Test void rejectsInvalidCountryAndTimeRatherThanNormalizingOrWrapping() {
        for (String invalid : new String[]{"", "C", "CHN", "cn", "Cn", " CN", "CN ", "中N", "ÅA", "A1"}) {
            assertThrows(IllegalArgumentException.class, () -> CountryIndex.code(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> CountryIndex.code(null));
        assertThrows(IllegalArgumentException.class, () -> CountryIndex.score("CN", CountryIndex.EPOCH_MILLIS - 1));
        assertThrows(IllegalArgumentException.class, () -> CountryIndex.score("CN", CountryIndex.EPOCH_MILLIS + CountryIndex.TIME_SCALE));
    }
}
