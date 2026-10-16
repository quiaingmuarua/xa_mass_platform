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

    @Test void primeValidationCompletesBeforeAnyBatchAndPreservesTextIdentity() throws Exception {
        try (var file = AppCheckSeedFile.read(input("\uFEFF 17 \n017\n\t17\t\n\n4294967295"), AppCheckContracts.uint32Prime(), null)) {
            assertThat(Files.readAllLines(file.file())).containsExactly("17", "017", "4294967295");
            assertThat(file.duplicateCount()).isEqualTo(1);
            assertThat(file.emptyCount()).isEqualTo(1);
        }
        String validBatch = String.join("\n", IntStream.range(0, 201).mapToObj(Integer::toString).toList());
        assertThatThrownBy(() -> AppCheckSeedFile.read(input(validBatch + "\n4294967296"), AppCheckContracts.uint32Prime(), null))
                .hasMessageContaining("第 202 行").hasMessageContaining("未写入");
    }

    @Test void textOnlyTrimsSpacesAndTabsAndDeduplicatesWithoutChangingUnicodeOrCase() throws Exception {
        java.nio.file.Path path;
        try (var file = AppCheckSeedFile.read(input("\uFEFF a \r\n\tabc\t\n\nA\n中文\n a \na b\n{\"x\":1}\n"), AppCheckContracts.md5Prefix(), null)) {
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
        try (var file = AppCheckSeedFile.read(input("86123\n+86123\n86124"), AppCheckContracts.registration(), "CN")) {
            assertThat(Files.readAllLines(file.file())).containsExactly("+86123", "+86124");
            assertThat(file.duplicateCount()).isEqualTo(1);
        }
        assertThatThrownBy(() -> AppCheckSeedFile.read(input("86123\n86124\n12025550123"), AppCheckContracts.registration(), "CN"))
                .hasMessageContaining("第 3 行").hasMessageContaining("未写入");
        assertThatThrownBy(() -> AppCheckSeedFile.read(input("a"), AppCheckContracts.md5Prefix(), "CN"))
                .hasMessageContaining("不接受 country");
    }

    @Test void bothFormatsRejectMalformedUtf8AndKeepTheirRequestCapacity() throws Exception {
        for (var type : AppCheckContract.SeedType.values()) {
            String country = type == AppCheckContract.SeedType.PHONENUMBER ? "CN" : null;
            assertThatThrownBy(() -> AppCheckSeedFile.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), type == AppCheckContract.SeedType.TEXT ? AppCheckContracts.md5Prefix() : AppCheckContracts.registration(), country))
                    .hasMessageContaining("UTF-8");
            assertThatThrownBy(() -> AppCheckSeedFile.read(new ByteArrayInputStream(new byte[AppCheckSeedFile.MAX_BYTES + 1]), type == AppCheckContract.SeedType.TEXT ? AppCheckContracts.md5Prefix() : AppCheckContracts.registration(), country))
                    .hasMessageContaining("10 MiB");
        }
        String seeds = String.join("\n", IntStream.range(0,100_000).mapToObj(i -> "seed-" + i).toList());
        try (var file = AppCheckSeedFile.read(input(seeds), AppCheckContracts.md5Prefix(), null)) {
            assertThat(file.uniqueCount()).isEqualTo(100_000);
        }
        assertThatThrownBy(() -> AppCheckSeedFile.read(input(seeds + "\nextra"), AppCheckContracts.md5Prefix(), null))
                .hasMessageContaining("100,000");
    }
}
