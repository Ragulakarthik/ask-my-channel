package com.karthik.askmychannel.entity;

/**
 * Where a chunk's text came from — lets the chat prompt label excerpts so the LLM (and,
 * later, the UI) can weigh a viewer comment differently from the creator's own words.
 */
public enum ChunkSource {
    TRANSCRIPT,
    DESCRIPTION,
    COMMENT
}
