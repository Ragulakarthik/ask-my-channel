package com.karthik.askmychannel.dto;

/**
 * Public, read-only — which channel the chat page should talk to. Both fields null means
 * nothing is configured yet (a fresh clone before its first ingest).
 */
public record ActiveChannelResponse(String channelHandle, String channelTitle) {
}
