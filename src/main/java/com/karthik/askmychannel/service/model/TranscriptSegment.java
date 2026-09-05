package com.karthik.askmychannel.service.model;

/**
 * One timed caption event, already stripped of markup. durationSeconds may be 0 for
 * zero-length "roll-up" filler events, which callers should skip.
 */
public record TranscriptSegment(String text, double startSeconds, double durationSeconds) {
}
