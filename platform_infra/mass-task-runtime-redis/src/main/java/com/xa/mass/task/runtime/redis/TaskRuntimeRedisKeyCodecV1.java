package com.xa.mass.task.runtime.redis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class TaskRuntimeRedisKeyCodecV1 {

    String encodeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("key segment is required");
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var builder = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int valueByte = raw & 0xFF;
            char ch = (char) valueByte;
            if (isAllowed(ch)) {
                builder.append(ch);
            } else {
                builder.append('%');
                builder.append(toHex((valueByte >>> 4) & 0x0F));
                builder.append(toHex(valueByte & 0x0F));
            }
        }
        return builder.toString();
    }

    String decodeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("key segment is required");
        }
        var output = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '%' && index + 2 < value.length()) {
                int high = fromHex(value.charAt(index + 1));
                int low = fromHex(value.charAt(index + 2));
                if (high >= 0 && low >= 0) {
                    output.write((high << 4) | low);
                    index += 2;
                    continue;
                }
            }
            output.write((byte) ch);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static boolean isAllowed(char ch) {
        return ch >= 'A' && ch <= 'Z'
                || ch >= 'a' && ch <= 'z'
                || ch >= '0' && ch <= '9'
                || ch == '.'
                || ch == '_'
                || ch == '-';
    }

    private static char toHex(int value) {
        return (char) (value < 10 ? '0' + value : 'A' + (value - 10));
    }

    private static int fromHex(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }
}
