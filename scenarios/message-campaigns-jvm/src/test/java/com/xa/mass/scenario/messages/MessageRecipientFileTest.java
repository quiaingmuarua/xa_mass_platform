package com.xa.mass.scenario.messages;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MessageRecipientFileTest {
    @Test void acceptsTheBoundedFileAndCleansMaterialsOnSuccessAndValidationFailure() throws Exception {
        Set<Path> before = materials();
        String numbers = IntStream.range(0, 100000).mapToObj(i -> "86138" + (10000000 + i)).collect(java.util.stream.Collectors.joining("\n"));
        try (var file = MessageRecipientFile.read(input(numbers), "CN")) {
            assertThat(file.uniqueCount()).isEqualTo(100000); assertThat(Files.size(file.file())).isPositive();
        }
        for (String text : List.of("", "+86", "000123", "+44123", "86123\nbad", numbers + "\n8613911111111"))
            assertThatThrownBy(() -> MessageRecipientFile.read(input(text), "CN")).isInstanceOf(MessageError.class);
        assertThatThrownBy(() -> MessageRecipientFile.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), "CN"))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> MessageRecipientFile.read(input(" ".repeat(MessageRecipientFile.MAX_BYTES + 1)), "CN"))
                .isInstanceOfSatisfying(MessageError.class, e -> assertThat(e.status).isEqualTo(413));
        try (var file = MessageRecipientFile.read(input("86123" + " ".repeat(MessageRecipientFile.MAX_BYTES - 5)), "CN")) {
            assertThat(file.uniqueCount()).isEqualTo(1);
        }
        assertThat(materials()).isEqualTo(before);
    }
    private static InputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static Set<Path> materials() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("message-input-") || p.getFileName().toString().startsWith("message-recipients-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
