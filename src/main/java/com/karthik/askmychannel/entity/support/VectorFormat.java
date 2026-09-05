package com.karthik.askmychannel.entity.support;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Converts between a Java float[] and pgvector's text literal format, e.g. "[0.1,0.2,0.3]".
 * Shared by {@link VectorType} (entity mapping) and ChatService (native query parameter).
 */
public final class VectorFormat {

    private VectorFormat() {
    }

    public static String toLiteral(float[] values) {
        return "[" + IntStream.range(0, values.length)
                .mapToObj(i -> Float.toString(values[i]))
                .collect(Collectors.joining(",")) + "]";
    }

    public static float[] fromLiteral(String raw) {
        String trimmed = raw.substring(1, raw.length() - 1);
        if (trimmed.isBlank()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i]);
        }
        return result;
    }
}
