package com.xa.mass.sdk.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Text payload wrapper for SDK v1 task inputs.
 */
public final class TextInput implements MassInput {

    private final String text;

    public TextInput(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public String getText() {
        return text;
    }

    @Override
    public Map<String, Object> toTaskMsgInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "text");
        input.put("text", text);
        return Map.copyOf(input);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextInput textInput)) return false;
        return Objects.equals(text, textInput.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "TextInput{" +
                "text='" + text + '\'' +
                '}';
    }
}
