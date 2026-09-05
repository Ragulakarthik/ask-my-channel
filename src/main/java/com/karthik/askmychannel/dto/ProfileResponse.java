package com.karthik.askmychannel.dto;

/**
 * Never carries the actual API key values back to the browser — only whether one is
 * currently configured (via profile override or env var), so the UI can show
 * "already set" vs "not set" without ever round-tripping a secret.
 */
public record ProfileResponse(String channelHandle, boolean hasGeminiKey, boolean hasGroqKey) {
}
