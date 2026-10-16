package com.xa.mass.scenario.appchecks;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckSeedFileTest {
    ByteArrayInputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    @Test void textOnlyTrimsSpacesAndTabsAndDeduplicatesWithoutChangingUnicodeOrCase() throws Exception {
        java.nio.file.Path path;
        try (var file = AppCheckSeedFile.read(input("\uFEFF a \r\n\tabc\t\n\nA\n中文\n a \na b\n{\"x\":1}\n"), AppCheckContract.SeedType.TEXT, null)) {
            path = file.file();
            assertThat(Files.readAllLines(path)).containsExactly("a", "abc", "A", "中文", "a b", "{\"x\":1}");
            assertThat(file.inputCount()).isEqualTo(8);
            assertThat(file.emptyCount()).isEqualTo(1);
            assertThat(file.duplicateCount()).isEqualTo(1);
        }
        assertThat(Files.exists(path)).isFalse();
        assertThat(AppCheckContract.SeedType.TEXT.messageId("a")).isNotEqualTo(AppCheckContract.SeedType.TEXT.messageId("A"));
        assertThat(AppCheckContract.SeedType.TEXT.messageId("+86123")).isNotEqualTo(AppCheckContract.SeedType.PHONENUMBER.messageId("+86123"));
    }

    @Test void keepsPhoneCanonicalizationAndRejectsInvalidFinalLineBeforeReturningAnyInput() throws Exception {
        try (var file = AppCheckSeedFile.read(input("86123\n+86123\n86124"), AppCheckContract.SeedType.PHONENUMBER, "CN")) {
            assertThat(Files.readAllLines(file.file())).containsExactly("+86123", "+86124");
            assertThat(file.duplicateCount()).isEqualTo(1);
        }
        assertThatThrownBy(() -> AppCheckSeedFile.read(input("86123\n86124\n12025550123"), AppCheckContract.SeedType.PHONENUMBER, "CN"))
                .hasMessageContaining("第 3 行").hasMessageContaining("未写入");
        assertThatThrownBy(() -> AppCheckSeedFile.read(input("a"), AppCheckContract.SeedType.TEXT, "CN"))
                .hasMessageContaining("不接受 country");
    }

    @Test void bothFormatsRejectMalformedUtf8AndKeepTheirRequestCapacity() throws Exception {
        for (var type : AppCheckContract.SeedType.values()) {
            String country = type == AppCheckContract.SeedType.PHONENUMBER ? "CN" : null;
            assertThatThrownBy(() -> AppCheckSeedFile.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), type, country))
                    .hasMessageContaining("UTF-8");
            assertThatThrownBy(() -> AppCheckSeedFile.read(new ByteArrayInputStream(new byte[AppCheckSeedFile.MAX_BYTES + 1]), type, country))
                    .hasMessageContaining("10 MiB");
        }
        String seeds = String.join("\n", IntStream.range(0,100_000).mapToObj(i -> "seed-" + i).toList());
        try (var file = AppCheckSeedFile.read(input(seeds), AppCheckContract.SeedType.TEXT, null)) {
            assertThat(file.uniqueCount()).isEqualTo(100_000);
        }
        assertThatThrownBy(() -> AppCheckSeedFile.read(input(seeds + "\nextra"), AppCheckContract.SeedType.TEXT, null))
                .hasMessageContaining("100,000");
    }
}
