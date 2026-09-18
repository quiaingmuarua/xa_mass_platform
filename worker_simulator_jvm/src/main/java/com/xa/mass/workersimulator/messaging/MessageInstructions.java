package com.xa.mass.workersimulator.messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/** The finite Preview body protocol, interpreted only by the receiving Lab. */
record MessageInstructions(List<String> requested, List<String> retained, List<Integer> delays,
        boolean droppedLast, String reply) {
    static MessageInstructions parse(String body, long seed, String messageId) {
        try {
            // Reject duplicate fields as well as malformed/lenient JSON before making any fact.
            JsonReader reader = new JsonReader(new StringReader(body));
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            Set<String> fields = new java.util.HashSet<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!Set.of("receipts_status", "delayMs", "probability", "text").contains(field)
                        || !fields.add(field)) throw new IllegalArgumentException("Invalid instruction field");
                reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Invalid instruction JSON");
            var json = JsonParser.parseString(body).getAsJsonObject();
            List<String> requested = new ArrayList<>();
            if (json.has("receipts_status")) {
                if (!json.get("receipts_status").isJsonArray()) throw new IllegalArgumentException("Invalid receipts_status");
                for (JsonElement value : json.getAsJsonArray("receipts_status")) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Invalid receipt status");
                    String status = value.getAsString();
                    if (!(status.equals("replied") || status.equals("read") && requested.isEmpty()))
                        throw new IllegalArgumentException("Invalid receipt order");
                    requested.add(status);
                    if (requested.size() > 16) throw new IllegalArgumentException("At most 16 receipt steps");
                }
            }
            int low = 1000, high = 4000;
            if (json.has("delayMs")) {
                JsonElement delay = json.get("delayMs");
                if (delay.isJsonArray()) {
                    if (delay.getAsJsonArray().size() != 2) throw new IllegalArgumentException("Invalid delay range");
                    low = delay(delay.getAsJsonArray().get(0)); high = delay(delay.getAsJsonArray().get(1));
                } else low = high = delay(delay);
                if (low > high) throw new IllegalArgumentException("Invalid delay range");
            }
            double probability = 0;
            if (json.has("probability")) {
                var number = json.get("probability");
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("Invalid probability");
                probability = number.getAsDouble();
                if (!Double.isFinite(probability) || probability < 0 || probability > 1)
                    throw new IllegalArgumentException("Invalid probability");
            }
            String text = null;
            if (json.has("text")) {
                var value = json.get("text");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Invalid reply text");
                text = value.getAsString();
            }
            if (requested.contains("replied") && (text == null || text.isBlank())) throw new IllegalArgumentException("Replies require text");
            long mixedSeed = seed;
            for (int i = 0; i < messageId.length(); i++) mixedSeed = mixedSeed * 31 + messageId.charAt(i);
            var random = new SplittableRandom(mixedSeed);
            boolean dropped = !requested.isEmpty() && random.nextDouble() < probability;
            List<String> retained = List.copyOf(requested.subList(0, requested.size() - (dropped ? 1 : 0)));
            List<Integer> delays = new ArrayList<>();
            for (String ignored : retained) delays.add(random.nextInt(low, high + 1));
            return new MessageInstructions(List.copyOf(requested), retained, List.copyOf(delays), dropped, text);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid message instruction JSON", invalid);
        }
    }

    private static int delay(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid delayMs");
        try {
            int result = value.getAsBigDecimal().intValueExact();
            if (result < 0 || result > 60_000) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid delayMs"); }
    }
}
