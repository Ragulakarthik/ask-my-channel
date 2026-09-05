package com.karthik.askmychannel.service.model;

import java.util.List;

/**
 * Result of listing a channel's uploads: the resolved (stable) channel id and title, plus
 * every video found. The handle a creator gives us can change; channelId is what we key on.
 */
public record ChannelVideos(String channelId, String channelTitle, List<VideoMetadata> videos) {
}
