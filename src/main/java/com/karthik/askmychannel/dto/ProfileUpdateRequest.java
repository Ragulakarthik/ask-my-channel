package com.karthik.askmychannel.dto;

/**
 * Any field left blank/null means "don't change this one" — a deployer can update just the
 * channel handle without re-entering keys they already saved, and vice versa.
 */
public record ProfileUpdateRequest(String channelHandle, String geminiApiKey, String groqApiKey) {
}
