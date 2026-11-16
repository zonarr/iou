package io.github.zonarr.iou.support;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Small helpers for building request bodies and reading money out of responses. */
public final class Json {

    private Json() {
    }

    /** {@code body("amount", "10.50", "currency", "EUR")}: a map that may hold nulls and numbers. */
    public static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Reads a JSON number exactly, e.g. to compare with {@code new BigDecimal("33.34")}. */
    public static BigDecimal money(JsonNode node, String field) {
        return node.get(field).decimalValue();
    }

    public static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
