package com.karthik.askmychannel.service.model;

import java.util.List;

/**
 * Everything ingestible fetched for one video in a single yt-dlp call: transcript segments
 * (may be empty if captions were unavailable or the caption endpoint was rate-limited),
 * the video description, and a capped set of top comments (most-liked first).
 */
public record VideoContent(List<TranscriptSegment> transcript, String description, List<String> topComments) {
}
