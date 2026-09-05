package com.karthik.askmychannel.service.model;

import java.time.Instant;

/**
 * One video as listed from a channel, before any transcript has been fetched.
 */
public record VideoMetadata(String videoId, String title, Instant publishedAt, Integer durationSeconds) {
}
