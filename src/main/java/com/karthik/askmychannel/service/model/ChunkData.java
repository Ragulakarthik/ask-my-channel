package com.karthik.askmychannel.service.model;

/**
 * A time-windowed group of transcript text, not yet embedded or persisted.
 */
public record ChunkData(String text, double startSeconds) {
}
