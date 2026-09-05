package com.karthik.askmychannel.dto;

import com.karthik.askmychannel.entity.Channel;

public record ChannelSummaryResponse(String channelId, String handle, String title, long videoCount) {

    public static ChannelSummaryResponse from(Channel channel, long videoCount) {
        return new ChannelSummaryResponse(channel.getChannelId(), channel.getHandle(), channel.getTitle(), videoCount);
    }
}
